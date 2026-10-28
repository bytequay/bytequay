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
package com.bytequay.app.repository.github;

import com.bytequay.app.repository.github.actions.CiHeadSnapshot;
import com.bytequay.app.repository.github.actions.CiRerunResult;
import com.bytequay.app.repository.github.actions.CiWorkflowRun;
import com.bytequay.app.repository.github.actions.RerunFailedJobsCommand;
import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.hosting.SubmittedReview;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestWriter
        implements PullRequestWriter
{
    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestWriter.class);
    private final GitHubPullRequestReadClient reads;
    private final GitHubPullRequestWriteClient writes;
    private final GitHubActionsClient actions;

    public GitHubPullRequestWriter(GitHubPullRequestReadClient reads, GitHubPullRequestWriteClient writes, GitHubActionsClient actions)
    {
        this.actions = requireNonNull(actions, "actions is null");
        this.reads = requireNonNull(reads, "reads is null");
        this.writes = requireNonNull(writes, "writes is null");
    }

    @Override
    public HostingProvider provider()
    {
        return HostingProvider.GITHUB;
    }

    private static PullRequestRef ref(HostingAccess access, HostingRepository repository, int number)
    {
        checkArgument(access.server().equals(GITHUB_SERVER), "This adapter requires GitHub.com");
        return parseRef(repository.fullPath(), number);
    }

    @Override
    public PrTimelineEvent comment(HostingAccess access, HostingRepository repository, int number, String body)
    {
        return writes.createIssueComment(access.token(), ref(access, repository, number), body);
    }

    @Override
    public PrTimelineEvent createReviewComment(HostingAccess access, HostingRepository repository, int number, InlineReviewComment comment)
    {
        return writes.createReviewComment(access.token(), ref(access, repository, number), comment);
    }

    @Override
    public void replyToReviewComment(HostingAccess access, HostingRepository repository, int number, long commentId, String body)
    {
        writes.replyToReviewComment(access.token(), ref(access, repository, number), commentId, body);
    }

    @Override
    public void updateTitle(HostingAccess access, HostingRepository repository, int number, String title)
    {
        writes.updatePullRequestTitle(access.token(), ref(access, repository, number), title);
    }

    @Override
    public void updateDescription(HostingAccess access, HostingRepository repository, int number, String description)
    {
        writes.updatePullRequestDescription(access.token(), ref(access, repository, number), description);
    }

    @Override
    public void close(HostingAccess access, HostingRepository repository, int number)
    {
        writes.closePullRequest(access.token(), ref(access, repository, number));
    }

    @Override
    public void markReady(HostingAccess access, HostingRepository repository, int number)
    {
        writes.markReadyForReview(access.token(), node(access, repository, number));
    }

    @Override
    public void updateMetadata(HostingAccess access, HostingRepository repository, int number, List<String> assignees, List<String> labels)
    {
        writes.updateIssueMetadata(access.token(), ref(access, repository, number), assignees, labels);
    }

    @Override
    public void updateReviewer(HostingAccess access, HostingRepository repository, int number, String login, boolean requested)
    {
        writes.updateReviewerRequest(access.token(), ref(access, repository, number), login, requested);
    }

    @Override
    public void resolveThread(HostingAccess access, HostingRepository repository, int number, long commentId, boolean resolved)
    {
        writes.setReviewThreadResolved(access.token(), reads.findReviewThreadId(access.token(), ref(access, repository, number), commentId), resolved);
    }

    @Override
    public void editComment(HostingAccess access, HostingRepository repository, int number, String target, long commentId, String body)
    {
        PullRequestRef ref = ref(access, repository, number);
        switch (target) {
            case "issue_comment" -> writes.updateIssueComment(access.token(), ref, commentId, body);
            case "review_comment" -> writes.updateReviewComment(access.token(), ref, commentId, body);
            case "review" -> writes.updateReviewBody(access.token(), ref, commentId, body);
            default -> throw new IllegalArgumentException("Unsupported comment target");
        }
    }

    @Override
    public boolean toggleReaction(HostingAccess access, HostingRepository repository, int number, String target, Long commentId, String content, Boolean viewerHasReacted)
    {
        PullRequestRef ref = ref(access, repository, number);
        return switch (target) {
            case "pull_request" -> writes.togglePullRequestReaction(access.token(), ref, content, viewerHasReacted);
            case "issue_comment" -> writes.toggleIssueCommentReaction(access.token(), ref, commentId, content, viewerHasReacted);
            case "review_comment" -> writes.toggleReviewCommentReaction(access.token(), ref, commentId, content, viewerHasReacted);
            default -> throw new IllegalArgumentException("Unsupported reaction target");
        };
    }

    @Override
    public SubmittedReview submitReview(HostingAccess access, HostingRepository repository, int number, String event, String body,
            List<InlineReviewComment> comments, String commitId)
    {
        PullRequestRef ref = ref(access, repository, number);
        String token = access.token();
        Map<Long, String> replies = new LinkedHashMap<>();
        comments.stream().map(InlineReviewComment::replyToCommentId).filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong).distinct()
                .forEach(id -> replies.put(id, reads.findReviewThreadId(token, ref, id)));
        PrReviewState created = replies.isEmpty() ? writes.submitReview(token, ref, event, body, comments, commitId)
                : writes.submitReviewWithReplies(token, ref, event, body, comments, replies, commitId);
        List<PrTimelineEvent> threads = ImmutableList.of();
        if (!comments.isEmpty() && created.githubId() != null) {
            try {
                threads = replies.isEmpty() ? reads.fetchReviewComments(token, ref, created.githubId()) : reads.fetchPrReviewThreads(token, ref);
            }
            catch (RuntimeException failure) {
                log.warn("Review was posted but its threads could not be read: {}", failure.toString());
            }
        }
        return new SubmittedReview(Optional.of(created), threads);
    }

    @Override
    public MergeResult merge(HostingAccess access, HostingRepository repository, int number, String strategy, String headSha)
    {
        PullRequestRef ref = ref(access, repository, number);
        String token = access.token();
        if ("when-ready".equalsIgnoreCase(strategy)) {
            return writes.enablePullRequestAutoMerge(token, node(access, repository, number), headSha);
        }
        if ("queue".equalsIgnoreCase(strategy)) {
            return writes.enqueuePullRequest(token, node(access, repository, number), headSha);
        }
        String requested = strategy == null || "default".equalsIgnoreCase(strategy) ? "rebase" : strategy.toLowerCase(Locale.ROOT);
        String method = switch (requested) {
            case "squash" -> "squash";
            case "merge" -> "merge";
            default -> "rebase";
        };
        try {
            return writes.mergePullRequest(token, ref, new MergePullRequestCommand(method, Optional.empty(), Optional.empty(), Optional.of(headSha)));
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 405 && failure.getReason() != null && failure.getReason().toLowerCase(Locale.ROOT).contains("merge queue")) {
                return writes.enqueuePullRequest(token, node(access, repository, number), headSha);
            }
            throw failure;
        }
    }

    @Override
    public void removeFromMergeQueue(HostingAccess access, HostingRepository repository, int number, boolean autoMerge)
    {
        String nodeId = node(access, repository, number);
        if (autoMerge) {
            writes.disablePullRequestAutoMerge(access.token(), nodeId);
        }
        else {
            writes.dequeuePullRequest(access.token(), nodeId);
        }
    }

    @Override
    public boolean hasMergeQueue(HostingAccess access, HostingRepository repository, String baseRef)
    {
        PullRequestRef ref = ref(access, repository, 1);
        return writes.hasMergeQueue(access.token(), RepoRef.of(ref.owner(), ref.repo()), baseRef);
    }

    @Override
    public CreatedPullRequest create(HostingAccess access, HostingRepository repository, String head, String base, String title, String body, boolean draft)
    {
        PullRequestRef ref = ref(access, repository, 1);
        return writes.createPullRequest(access.token(), RepoRef.of(ref.owner(), ref.repo()), head, base, title, body, draft);
    }

    @Override
    public void rerunFailedChecks(HostingAccess access, HostingRepository repository, int number)
    {
        PullRequestRef pullRequest = ref(access, repository, number);
        CiHeadSnapshot head = actions.inspect(access.token(), pullRequest);
        List<CiWorkflowRun> failedRuns = head.workflowRuns().stream()
                .filter(run -> run.jobs().stream().anyMatch(job -> job.actionableFailure()))
                .collect(ImmutableList.toImmutableList());
        if (failedRuns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No failed GitHub Actions jobs are available to rerun");
        }
        int accepted = 0;
        String rejection = "GitHub did not accept the failed-job rerun";
        for (CiWorkflowRun run : failedRuns) {
            CiRerunResult result = actions.rerunFailedJobs(access.token(), new RerunFailedJobsCommand(pullRequest, run.id(), head.headSha(), run.attempt(), true));
            if (result.outcome() == CiRerunResult.Outcome.ACCEPTED) {
                accepted++;
            }
            else {
                rejection = result.reason();
            }
        }
        if (accepted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, rejection);
        }
    }

    private String node(HostingAccess access, HostingRepository repository, int number)
    {
        return writes.pullRequestNodeId(access.token(), ref(access, repository, number))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
    }
}
