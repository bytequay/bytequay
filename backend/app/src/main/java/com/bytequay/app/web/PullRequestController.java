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
import com.bytequay.app.service.pr.PullRequestRoute;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestCheckLog;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
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
    public PullRequestPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size)
    {
        return pullRequests.listPullRequests(page, size);
    }

    @GetMapping("/prs/attention")
    public List<AttentionPullRequest> attention(WebRequest request, @RequestParam(required = false) String workspaceId)
    {
        // Electron polls this every few seconds, and most polls find nothing new: answer those without rebuilding every row.
        if (workspaceId == null && request.checkNotModified(pullRequests.attentionVersion())) {
            return null;
        }
        List<PullRequest> candidates = workspaceId == null ? pullRequests.listAttentionCandidates() : pullRequests.listWorkspaceAttentionCandidates(workspaceId);
        Map<Long, String> routes = new HashMap<>();
        if (workspaceId == null) {
            // Hosted rows open through the watched workspace that lists them.
            List<PullRequest> rows = new ArrayList<>(candidates);
            pullRequests.listHostedAttentionCandidates().forEach((workspace, hosted) -> hosted.forEach(row -> {
                rows.add(row);
                routes.put(row.id(), workspace);
            }));
            candidates = rows;
        }
        Map<Long, String> avatars = pullRequests.findCachedAuthorAvatarUrls(candidates.stream().map(PullRequest::id).collect(toImmutableSet()));
        Map<Long, PrActivitySummary> activities = pullRequests.findCachedLatestActivities(candidates);
        Map<Long, Instant> attentionActivities = pullRequests.findCachedAttentionActivities(candidates);
        Map<Long, List<PrCommitLine>> commits = pullRequests.findCachedCommitsSinceReview(candidates);
        Map<Long, List<String>> failingChecks = pullRequests.findCachedFailingChecks(candidates);
        Set<Long> mentioned = pullRequests.findMentioned(candidates);
        return candidates.stream()
                .map(pullRequest -> new AttentionPullRequest(pullRequest, avatars.get(pullRequest.id()), activities.get(pullRequest.id()),
                        attentionActivities.get(pullRequest.id()), commits.getOrDefault(pullRequest.id(), ImmutableList.of()),
                        failingChecks.getOrDefault(pullRequest.id(), ImmutableList.of()), mentioned.contains(pullRequest.id()), routes.get(pullRequest.id())))
                .collect(toImmutableList());
    }

    /**
     * A pull request as Needs attention draws it, with the cached facts its card details: nothing here reads the provider. Its attention
     * activity is the latest that ends an acknowledgment. A request listed through a watched workspace names that workspace.
     */
    public record AttentionPullRequest(
            @JsonUnwrapped PullRequest pullRequest,
            String authorAvatarUrl,
            PrActivitySummary latestActivity,
            Instant attentionActivityAt,
            List<PrCommitLine> commitsSinceReview,
            List<String> failingChecks,
            boolean mentioned,
            String workspaceId) {}

    @GetMapping("/prs/stored")
    public Optional<PullRequest> stored(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.findStored(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    @GetMapping("/prs/lookup")
    public PullRequest lookup(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.lookupPullRequest(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    /** For a hover card, which must neither reach GitHub nor mark the pull request viewed the way opening its detail does. */
    @GetMapping("/prs/stored/description")
    public StoredDescription storedDescription(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return new StoredDescription(pullRequests.findStoredDescription(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number)));
    }

    /** Empty until a detail read has stored the pull request; an empty string is a pull request with no description. */
    public record StoredDescription(Optional<String> body) {}

    @PatchMapping("/prs/{prId}/monitor")
    public PullRequest monitor(@PathVariable long prId, @RequestBody MonitorRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        checkArgument(request.monitored() != null, "monitored is required");
        return pullRequests.setMonitored(prId, request.monitored(), PullRequestRoute.of(workspaceId, accountId));
    }

    public record MonitorRequest(Boolean monitored) {}

    @GetMapping("/prs/users")
    public List<RepoUser> users(@RequestParam("repo") String repo, @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.listRepositoryUsers(repo, PullRequestRoute.of(workspaceId, accountId));
    }

    @GetMapping("/prs/teams")
    public List<String> teams(@RequestParam("repo") String repo, @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.listRepositoryTeams(repo, PullRequestRoute.of(workspaceId, accountId));
    }

    @GetMapping("/prs/labels")
    public List<RepositoryLabel> labels(@RequestParam("repo") String repo, @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.listRepositoryLabels(repo, PullRequestRoute.of(workspaceId, accountId));
    }

    @PostMapping("/api/settings/sync/trigger")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void triggerSync(@RequestParam(defaultValue = "false") boolean listOnly)
    {
        if (listOnly) {
            refreshService.refreshDashboardRows();
            return;
        }
        refreshService.refreshDashboard();
    }

    @GetMapping("/prs/detail")
    public PullRequestDetail detail(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.openPullRequestDetail(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    @PostMapping("/prs/detail/refresh")
    public PullRequestDetailRefresh refreshDetail(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam(value = "checksOnly", defaultValue = "false") boolean checksOnly,
            @RequestParam(value = "opened", defaultValue = "false") boolean opened,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        if (opened) {
            return refreshService.refreshOpenedDetail(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
        }
        return checksOnly ? refreshService.refreshChecks(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number))
                : refreshService.refreshDetail(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    @PostMapping("/prs/detail/refresh-all")
    public PullRequestDetail refreshAllDetail(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return refreshService.refreshAllDetail(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    @GetMapping("/prs/diffFiles")
    public List<DiffFile> diffFiles(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam(value = "base", required = false) String baseSha,
            @RequestParam(value = "head", required = false) String headSha,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.getPullRequestDiffFiles(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), baseSha, headSha);
    }

    @GetMapping("/prs/diffCommits")
    public List<DiffCommit> diffCommits(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.getPullRequestDiffCommits(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    @PostMapping("/prs/diffFiles/refresh")
    public List<DiffFile> refreshDiffFiles(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam(value = "base", required = false) String baseSha,
            @RequestParam(value = "head", required = false) String headSha,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return refreshService.refreshDiffFiles(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), baseSha, headSha);
    }

    @PostMapping("/prs/diffContent/refresh")
    public DiffFileContent refreshDiffContent(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("path") String path,
            @RequestParam(value = "base", required = false) String baseSha,
            @RequestParam(value = "head", required = false) String headSha,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return refreshService.refreshDiffFileContent(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), path, baseSha, headSha);
    }

    @GetMapping("/prs/merge-mode")
    public Map<String, Boolean> mergeMode(@RequestParam("repo") String repo, @RequestParam("branch") String branch,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return ImmutableMap.of("mergeQueue", pullRequests.hasMergeQueue(repo, branch, PullRequestRoute.of(workspaceId, accountId)));
    }

    public record CommentRequest(String body) {}

    public record DescriptionRequest(String body) {}

    public record TitleRequest(String title) {}

    public record ResolutionRequest(boolean resolved) {}

    public record MetadataRequest(List<String> assignees, List<String> labels) {}

    public record ReviewerRequest(String login, boolean requested) {}

    public record ReactionRequest(
            String target,
            Long commentId,
            String content,
            Boolean viewerHasReacted) {}

    public record ReactionResponse(boolean viewerHasReacted) {}

    public record CommentEditRequest(String target, Long commentId, String body) {}

    @PatchMapping("/prs/title")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateTitle(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody TitleRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.updateTitle(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), request.title());
    }

    @PatchMapping("/prs/description")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateDescription(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody DescriptionRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.updateDescription(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), request.body());
    }

    @PatchMapping("/prs/metadata")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMetadata(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody MetadataRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.updateMetadata(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), request.assignees(), request.labels());
    }

    @PatchMapping("/prs/reviewers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateReviewer(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody ReviewerRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.updateReviewerRequest(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), request.login(), request.requested());
    }

    @PostMapping("/prs/comment")
    public PullRequestDetail comment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody CommentRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.commentOnPullRequest(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), request.body());
    }

    @PatchMapping("/prs/comments")
    public PullRequestDetail editComment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody CommentEditRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        checkArgument(request.commentId() != null, "commentId is required");
        return pullRequests.editComment(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), request.target(), request.commentId(), request.body());
    }

    @PostMapping("/prs/reactions")
    public ReactionResponse toggleReaction(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody ReactionRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return new ReactionResponse(pullRequests.toggleReaction(
                pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number),
                request.target(), request.commentId(), request.content(), request.viewerHasReacted()));
    }

    @PostMapping("/prs/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.closePullRequest(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    @PostMapping("/prs/ready-for-review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readyForReview(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.markReadyForReview(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    @GetMapping("/prs/checks/log")
    public PullRequestCheckLog readCheckLog(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("jobId") long jobId,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.readCheckLog(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), jobId);
    }

    @PostMapping("/prs/checks/rerun-failed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rerunFailedChecks(@RequestParam("repo") String repo, @RequestParam("number") int number,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.rerunFailedChecks(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number));
    }

    @PostMapping("/prs/review-comments/{commentId}/replies")
    public Map<String, String> replyToReviewComment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @PathVariable("commentId") long commentId,
            @RequestBody CommentRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.replyToReviewComment(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), commentId, request.body());
        return ImmutableMap.of("result", "replied");
    }

    @PostMapping("/prs/review-comments/{commentId}/resolution")
    public Map<String, String> setReviewThreadResolved(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @PathVariable("commentId") long commentId,
            @RequestBody ResolutionRequest request,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.setReviewThreadResolved(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), commentId, request.resolved());
        return ImmutableMap.of("result", request.resolved() ? "resolved" : "unresolved");
    }

    @DeleteMapping("/prs/merge-queue")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromMergeQueue(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam(value = "autoMerge", defaultValue = "false") boolean autoMerge,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        pullRequests.removeFromMergeQueue(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), autoMerge);
    }

    @PostMapping("/prs/merge")
    public MergeResult merge(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestParam("headSha") String headSha,
            @RequestParam(value = "strategy", required = false) String strategy,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) Long accountId)
    {
        return pullRequests.mergePullRequest(pullRequests.prepareRead(PullRequestRoute.of(workspaceId, accountId), repo, number), prId, strategy, headSha);
    }
}
