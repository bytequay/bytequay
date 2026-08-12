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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.remote.ReviewBuildCommentOperationHandler.CommentAction;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.RetryableActionException;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CreateReviewCommand.ReviewLineComment;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.GitHubAccountRepository;
import com.bytequay.app.repository.GitHubPullRequestReadRepository;
import com.bytequay.app.repository.GitHubPullRequestWriteRepository;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

/** GitHub adapter for one immutable, exact-head review publication. */
@Component
public final class GitHubReviewBuildCommentGateway
        implements ReviewBuildCommentOperationHandler.Gateway
{
    private final GitHubPullRequestReadRepository pullRequests;
    private final GitHubPullRequestWriteRepository pullRequestWrites;
    private final GitHubAccountRepository accounts;
    private final PatResolver pats;

    @Autowired
    public GitHubReviewBuildCommentGateway(
            GitHubPullRequestReadRepository pullRequests,
            GitHubPullRequestWriteRepository pullRequestWrites,
            GitHubAccountRepository accounts,
            PatResolver pats)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestWrites = requireNonNull(pullRequestWrites, "pullRequestWrites is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.pats = requireNonNull(pats, "pats is null");
    }

    GitHubReviewBuildCommentGateway(PullRequestRepository gitHub, PatResolver pats)
    {
        this(gitHub, gitHub, gitHub, pats);
    }

    @Override
    public List<String> captureBaseline(
            CommentAction action, ExecutionContext context)
            throws Exception
    {
        requireActive(context);
        Target target = requireExactMutationTarget(action);
        return pullRequests.listReviews(target.pat(), target.ref()).stream()
                .map(review -> "review:" + review.id())
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public EffectResult execute(
            CommentAction action, ExecutionContext context)
            throws Exception
    {
        EffectResult existing = probe(action, context);
        if (existing.proven()) {
            return existing;
        }
        Target target = requireExactMutationTarget(action);
        String body = nullToEmpty(action.payload().body());
        String reviewAction = requireReviewAction(
                action.payload().reviewAction());
        List<ReviewLineComment> comments = action.payload().drafts().stream()
                .filter(draft -> "file-line".equals(draft.scope()))
                .map(GitHubReviewBuildCommentGateway::lineComment)
                .toList();
        try {
            requireActive(context);
            pullRequestWrites.createReview(
                    target.pat(), target.ref(),
                    new CreateReviewCommand(
                            Optional.of(action.expectedHeadSha()),
                            body.isBlank() ? Optional.empty()
                                    : Optional.of(body),
                            reviewAction, comments));
            EffectResult submitted = probe(action, context);
            return submitted;
        }
        catch (ExecutionPorts.OperationCanceledException
                | ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            try {
                EffectResult recovered = probe(action, context);
                if (recovered.proven()) {
                    return recovered;
                }
            }
            catch (Exception probeFailure) {
                failure.addSuppressed(probeFailure);
            }
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "suggested-change review outcome is not independently proven",
                    failure);
        }
    }

    @Override
    public EffectResult probe(
            CommentAction action, ExecutionContext context)
            throws Exception
    {
        Target target = target(action);
        String viewer = requireText(
                accounts.fetchUserProfile(target.pat()).login(),
                "GitHub viewer login");
        String body = nullToEmpty(action.payload().body());
        String reviewAction = requireReviewAction(
                action.payload().reviewAction());
        String expectedState = expectedReviewState(reviewAction);
        List<PullRequestReview> matches = pullRequests.listReviews(
                        target.pat(), target.ref()).stream()
                .filter(review -> same(viewer, review.author()))
                .filter(review -> body.equals(nullToEmpty(review.body())))
                .filter(review -> expectedState.equals(normalize(review.state())))
                .filter(review -> action.expectedHeadSha().equals(
                        review.commitId()))
                .filter(review -> !baseline(action).contains(
                        "review:" + review.id()))
                .toList();
        if (matches.size() > 1) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "multiple matching suggested-change reviews prevent recovery");
        }
        if (matches.isEmpty()) {
            return new EffectResult(
                    false, null,
                    "no exact suggested-change review is observable");
        }
        PullRequestReview review = matches.getFirst();
        GitHubPullRequestReadRepository.Paged<PrReviewThreadMessage> commentPage =
                pullRequests.fetchAllPrReviewComments(
                        target.pat(), target.ref());
        if (!commentPage.complete()) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "suggested-change review comments were not read exhaustively");
        }
        List<PrReviewThreadMessage> actual = commentPage.items().stream()
                .filter(comment -> comment.reviewId() != null
                        && comment.reviewId() == review.id())
                .filter(comment -> comment.inReplyTo() == null)
                .toList();
        if (actual.stream().anyMatch(comment -> comment.commitId() != null
                && !action.expectedHeadSha().equals(comment.commitId()))) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "suggested-change review line comment commit differs from authorization");
        }
        Map<LineKey, Long> expectedLines = lineMultiset(
                action.payload().drafts());
        Map<LineKey, Long> actualLines = actualLineMultiset(actual);
        if (!expectedLines.equals(actualLines)) {
            boolean observableSubset = actualLines.entrySet().stream()
                    .allMatch(entry -> entry.getValue()
                            <= expectedLines.getOrDefault(entry.getKey(), 0L));
            if (observableSubset) {
                return new EffectResult(
                        false, null,
                        "review is visible; waiting for its exact inline comments");
            }
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "suggested-change review line comments differ from authorization");
        }
        return new EffectResult(
                true, "review:" + review.id(),
                "review " + review.id() + " " + reviewAction + " on "
                        + action.expectedHeadSha());
    }

    private Target requireExactMutationTarget(CommentAction action)
            throws RetryableActionException
    {
        Target target = target(action);
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(target.pat(), target.ref()),
                "GitHub returned no pull request detail");
        if (!"OPEN".equals(normalize(detail.state()))
                || detail.merged()
                || !action.expectedHeadSha().equals(detail.headSha())
                || !same(action.headRepositoryId(), detail.headRepo())
                || !action.branchName().equals(detail.headRef())
                || !same(action.remoteRepositoryId(), detail.baseRepo())) {
            throw new RetryableActionException(
                    "pull request moved outside the suggested-change authorization");
        }
        return target;
    }

    private Target target(CommentAction action)
    {
        RepoRef repository = RepoRef.parse(action.remoteRepositoryId());
        PullRequestRef ref = PullRequestRef.of(
                repository.owner(), repository.repo(),
                action.pullRequestNumber());
        return new Target(ref, pats.resolve(repository.fullName()));
    }

    private static ReviewLineComment lineComment(FrozenDraft draft)
    {
        return new ReviewLineComment(
                draft.filePath(), Optional.empty(),
                Optional.of(draft.lineNumber()), draft.side(), draft.body(),
                Optional.ofNullable(draft.startLine()),
                Optional.ofNullable(draft.startSide()));
    }

    private static Map<LineKey, Long> lineMultiset(List<FrozenDraft> drafts)
    {
        return drafts.stream()
                .filter(draft -> "file-line".equals(draft.scope()))
                .map(draft -> new LineKey(
                        draft.filePath(), draft.lineNumber(),
                        normalize(draft.side()), draft.startLine(),
                        normalizeNullable(draft.startSide()), draft.body()))
                .collect(Collectors.groupingBy(
                        Function.identity(), Collectors.counting()));
    }

    private static Map<LineKey, Long> actualLineMultiset(
            List<PrReviewThreadMessage> comments)
    {
        return comments.stream()
                .map(comment -> new LineKey(
                        comment.filePath(), comment.lineNumber(),
                        normalize(comment.side()), comment.startLine(),
                        normalizeNullable(comment.startSide()), comment.body()))
                .collect(Collectors.groupingBy(
                        Function.identity(), Collectors.counting()));
    }

    private static List<String> baseline(CommentAction action)
    {
        return requireNonNull(
                action.recoveryBaseline(),
                "suggested-change review recovery baseline is missing");
    }

    private static void requireActive(ExecutionContext context)
            throws ExecutionPorts.OperationCanceledException
    {
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "suggested-change review was canceled");
        }
    }

    private static boolean same(String left, String right)
    {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String normalize(String value)
    {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value)
    {
        return value == null ? null : normalize(value);
    }

    private static String requireReviewAction(String value)
    {
        String action = normalize(requireText(value, "reviewAction"));
        return switch (action) {
            case "COMMENT", "APPROVE", "REQUEST_CHANGES" -> action;
            default -> throw new IllegalArgumentException(
                    "unsupported review action " + value);
        };
    }

    private static String expectedReviewState(String action)
    {
        return switch (action) {
            case "COMMENT" -> "COMMENTED";
            case "APPROVE" -> "APPROVED";
            case "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
            default -> throw new IllegalArgumentException(
                    "unsupported review action " + action);
        };
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    private record Target(PullRequestRef ref, String pat) {}

    private record LineKey(
            String path,
            Integer line,
            String side,
            Integer startLine,
            String startSide,
            String body)
    {}
}
