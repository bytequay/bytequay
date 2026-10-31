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

import com.bytequay.core.ci.CiHeadSnapshot;
import com.bytequay.core.ci.CiRerunResult;
import com.bytequay.core.ci.CiRun;
import com.bytequay.core.ci.RerunFailedJobsCommand;
import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.CommentTarget;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.MergeStrategy;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.hosting.Reaction;
import com.bytequay.core.hosting.ReviewVerdict;
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

    private static PullRequestRef ref(HostingTarget target, int number)
    {
        checkArgument(target.access().server().equals(GITHUB_SERVER), "This adapter requires GitHub.com");
        return parseRef(target.repository().fullPath(), number);
    }

    @Override
    public PrTimelineEvent comment(HostingTarget target, int number, String body)
    {
        return writes.createIssueComment(target.access().token(), ref(target, number), body);
    }

    @Override
    public PrTimelineEvent createReviewComment(HostingTarget target, int number, InlineReviewComment comment)
    {
        return writes.createReviewComment(target.access().token(), ref(target, number), comment);
    }

    @Override
    public void replyToReviewComment(HostingTarget target, int number, long commentId, String body)
    {
        writes.replyToReviewComment(target.access().token(), ref(target, number), commentId, body);
    }

    @Override
    public void updateTitle(HostingTarget target, int number, String title)
    {
        writes.updatePullRequestTitle(target.access().token(), ref(target, number), title);
    }

    @Override
    public void updateDescription(HostingTarget target, int number, String description)
    {
        writes.updatePullRequestDescription(target.access().token(), ref(target, number), description);
    }

    @Override
    public void close(HostingTarget target, int number)
    {
        writes.closePullRequest(target.access().token(), ref(target, number));
    }

    @Override
    public void markReady(HostingTarget target, int number)
    {
        writes.markReadyForReview(target.access().token(), node(target, number));
    }

    @Override
    public void updateMetadata(HostingTarget target, int number, List<String> assignees, List<String> labels)
    {
        writes.updateIssueMetadata(target.access().token(), ref(target, number), assignees, labels);
    }

    @Override
    public void updateReviewer(HostingTarget target, int number, String login, boolean requested)
    {
        writes.updateReviewerRequest(target.access().token(), ref(target, number), login, requested);
    }

    @Override
    public void resolveThread(HostingTarget target, int number, long commentId, boolean resolved)
    {
        writes.setReviewThreadResolved(target.access().token(), reads.findReviewThreadId(target.access().token(), ref(target, number), commentId), resolved);
    }

    @Override
    public void editComment(HostingTarget target, int number, CommentTarget comment, long commentId, String body)
    {
        PullRequestRef ref = ref(target, number);
        switch (comment) {
            case ISSUE_COMMENT -> writes.updateIssueComment(target.access().token(), ref, commentId, body);
            case REVIEW_COMMENT -> writes.updateReviewComment(target.access().token(), ref, commentId, body);
            case REVIEW -> writes.updateReviewBody(target.access().token(), ref, commentId, body);
            case PULL_REQUEST -> throw new IllegalArgumentException("Unsupported comment target");
        }
    }

    @Override
    public boolean toggleReaction(HostingTarget target, int number, CommentTarget comment, Long commentId, Reaction reaction, Boolean viewerHasReacted)
    {
        PullRequestRef ref = ref(target, number);
        // The app spells reactions as GitHub's API does.
        String content = reaction.value();
        return switch (comment) {
            case PULL_REQUEST -> writes.togglePullRequestReaction(target.access().token(), ref, content, viewerHasReacted);
            case ISSUE_COMMENT -> writes.toggleIssueCommentReaction(target.access().token(), ref, commentId, content, viewerHasReacted);
            case REVIEW_COMMENT -> writes.toggleReviewCommentReaction(target.access().token(), ref, commentId, content, viewerHasReacted);
            case REVIEW -> throw new IllegalArgumentException("Unsupported reaction target");
        };
    }

    @Override
    public SubmittedReview submitReview(HostingTarget target, int number, ReviewVerdict verdict, String body,
            List<InlineReviewComment> comments, String commitId)
    {
        PullRequestRef ref = ref(target, number);
        String token = target.access().token();
        // GitHub's review events carry the verdict names.
        String event = verdict.name();
        Map<Long, String> replies = new LinkedHashMap<>();
        comments.stream().map(InlineReviewComment::replyToCommentId).filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong).distinct()
                .forEach(id -> replies.put(id, reads.findReviewThreadId(token, ref, id)));
        PrReviewState created = replies.isEmpty() ? writes.submitReview(token, ref, event, body, comments, commitId)
                : writes.submitReviewWithReplies(token, ref, event, body, comments, replies, commitId);
        List<PrTimelineEvent> threads = ImmutableList.of();
        if (!comments.isEmpty() && created.remoteId() != null) {
            try {
                threads = replies.isEmpty() ? reads.fetchReviewComments(token, ref, created.remoteId()) : reads.fetchPrReviewThreads(token, ref);
            }
            catch (RuntimeException failure) {
                log.warn("Review was posted but its threads could not be read: {}", failure.toString());
            }
        }
        return new SubmittedReview(Optional.of(created), threads);
    }

    @Override
    public MergeResult merge(HostingTarget target, int number, MergeStrategy strategy, String headSha)
    {
        PullRequestRef ref = ref(target, number);
        String token = target.access().token();
        if (strategy == MergeStrategy.WHEN_READY) {
            return writes.enablePullRequestAutoMerge(token, node(target, number), headSha);
        }
        if (strategy == MergeStrategy.QUEUE) {
            return writes.enqueuePullRequest(token, node(target, number), headSha);
        }
        // The default GitHub merge is a rebase.
        String method = switch (strategy) {
            case SQUASH -> "squash";
            case MERGE -> "merge";
            default -> "rebase";
        };
        try {
            return writes.mergePullRequest(token, ref, new MergePullRequestCommand(method, Optional.empty(), Optional.empty(), Optional.of(headSha)));
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 405 && failure.getReason() != null && failure.getReason().toLowerCase(Locale.ROOT).contains("merge queue")) {
                return writes.enqueuePullRequest(token, node(target, number), headSha);
            }
            throw failure;
        }
    }

    @Override
    public void removeFromMergeQueue(HostingTarget target, int number, boolean autoMerge)
    {
        String nodeId = node(target, number);
        if (autoMerge) {
            writes.disablePullRequestAutoMerge(target.access().token(), nodeId);
        }
        else {
            writes.dequeuePullRequest(target.access().token(), nodeId);
        }
    }

    @Override
    public boolean hasMergeQueue(HostingTarget target, String baseRef)
    {
        PullRequestRef ref = ref(target, 1);
        return writes.hasMergeQueue(target.access().token(), RepoRef.of(ref.owner(), ref.repo()), baseRef);
    }

    @Override
    public CreatedPullRequest create(HostingTarget target, RepoRef headRepository, String branch, String base, String title, String body, boolean draft)
    {
        PullRequestRef ref = ref(target, 1);
        // GitHub names a fork's branch owner:branch.
        String head = headRepository.fullName().equalsIgnoreCase(ref.repoFullName()) ? branch : headRepository.owner() + ":" + branch;
        return writes.createPullRequest(target.access().token(), RepoRef.of(ref.owner(), ref.repo()), head, base, title, body, draft);
    }

    @Override
    public void rerunFailedChecks(HostingTarget target, int number)
    {
        CiHeadSnapshot head = actions.inspect(target, number);
        List<CiRun> failedRuns = head.runs().stream()
                .filter(run -> run.jobs().stream().anyMatch(job -> job.actionableFailure()))
                .collect(ImmutableList.toImmutableList());
        if (failedRuns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No failed GitHub Actions jobs are available to rerun");
        }
        int accepted = 0;
        String rejection = "GitHub did not accept the failed-job rerun";
        for (CiRun run : failedRuns) {
            CiRerunResult result = actions.rerunFailedJobs(target, number, new RerunFailedJobsCommand(run.id(), head.headSha(), run.attempt(), true));
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

    private String node(HostingTarget target, int number)
    {
        return writes.pullRequestNodeId(target.access().token(), ref(target, number))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
    }
}
