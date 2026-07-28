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
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.RetryableActionException;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CreateReviewCommand.ReviewLineComment;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** GitHub adapter with an independent exact probe for every user action. */
@Component
public final class GitHubUserRemoteActionGateway
        implements UserRemoteActionOperationHandler.Gateway
{
    private final PullRequestRepository pullRequests;
    private final PatResolver pats;

    public GitHubUserRemoteActionGateway(
            PullRequestRepository pullRequests, PatResolver pats)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
    }

    @Override
    public List<String> captureBaseline(Action action, ExecutionContext context)
            throws Exception
    {
        requireNonNull(action, "action is null");
        requireActive(context);
        Target target = requireExactTarget(action);
        return switch (action.kind()) {
            case POST_TOP_LEVEL_COMMENT -> pullRequests.fetchPrIssueComments(
                            target.pat(), target.ref(),
                            recoveryFloor(action.authorizedAt())).stream()
                    .filter(event -> event.githubId() != null)
                    .map(event -> "issue-comment:" + event.githubId())
                    .distinct()
                    .sorted()
                    .toList();
            case SUBMIT_REVIEW -> pullRequests.listReviews(
                            target.pat(), target.ref()).stream()
                    .map(review -> "review:" + review.id())
                    .distinct()
                    .sorted()
                    .toList();
            case DEQUEUE, DELETE_REMOTE_BRANCH -> List.of();
        };
    }

    @Override
    public EffectResult execute(Action action, ExecutionContext context)
            throws Exception
    {
        requireNonNull(action, "action is null");
        EffectResult existing = probe(action, context);
        if (existing.proven()) {
            return existing;
        }
        requireActive(context);
        return switch (action.kind()) {
            case DEQUEUE -> dequeue(action, context);
            case DELETE_REMOTE_BRANCH -> deleteBranch(action, context);
            case POST_TOP_LEVEL_COMMENT -> postComment(action, context);
            case SUBMIT_REVIEW -> submitReview(action, context);
        };
    }

    @Override
    public EffectResult probe(Action action, ExecutionContext context)
            throws Exception
    {
        requireNonNull(action, "action is null");
        return switch (action.kind()) {
            case DEQUEUE -> probeDequeue(action);
            case DELETE_REMOTE_BRANCH -> probeDeletedBranch(action);
            case POST_TOP_LEVEL_COMMENT -> probeComment(action);
            case SUBMIT_REVIEW -> probeReview(action);
        };
    }

    private EffectResult dequeue(Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        try {
            requireActive(context);
            pullRequests.dequeuePullRequest(target.pat(), target.ref());
            return requireProven(
                    probeDequeue(action),
                    "GitHub still exposes the exact PR in its merge queue");
        }
        catch (ExecutionPorts.OperationCanceledException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult deleteBranch(Action action, ExecutionContext context)
            throws Exception
    {
        requireExactTarget(action);
        BranchTarget branch = branchTarget(action);
        try {
            requireActive(context);
            pullRequests.deleteBranch(
                    branch.pat(), branch.repository(), action.branchName());
            return requireProven(
                    probeDeletedBranch(action),
                    "GitHub still exposes the exact remote branch");
        }
        catch (ExecutionPorts.OperationCanceledException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult postComment(Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        String body = requireText(action.payload().body(), "comment body");
        try {
            requireActive(context);
            PrTimelineEvent created = requireNonNull(
                    pullRequests.createIssueComment(
                            target.pat(), target.ref(), body),
                    "GitHub returned no issue comment");
            if (created.githubId() == null || created.githubId() < 1
                    || !body.equals(created.body())) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "GitHub returned a non-exact issue comment");
            }
            return proven(
                    "issue-comment:" + created.githubId(),
                    "top-level comment " + created.githubId() + " on "
                            + action.headSha());
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult submitReview(Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        ActionPayload payload = action.payload();
        String reviewAction = requireReviewAction(payload.reviewAction());
        List<ReviewLineComment> comments = payload.drafts().stream()
                .filter(draft -> "file-line".equals(draft.scope()))
                .map(GitHubUserRemoteActionGateway::lineComment)
                .toList();
        String body = payload.body() == null ? "" : payload.body();
        try {
            requireActive(context);
            pullRequests.createReview(
                    target.pat(), target.ref(),
                    new CreateReviewCommand(
                            Optional.of(action.headSha()),
                            body.isBlank() ? Optional.empty() : Optional.of(body),
                            reviewAction, comments));
            return requireProven(
                    probeReview(action),
                    "GitHub did not expose the exact submitted review");
        }
        catch (ExecutionPorts.OperationCanceledException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult probeDequeue(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        PullRequestRepository.MergeQueueInfo queue = requireNonNull(
                pullRequests.fetchMergeQueueInfo(target.pat(), target.ref()),
                "GitHub returned no merge queue state");
        return queue.entryState() == null || queue.entryState().isBlank()
                ? proven(
                        "merge-queue:absent:" + action.remoteRepositoryId()
                                + "#" + action.pullRequestNumber(),
                        "exact PR is absent from the merge queue at "
                                + action.headSha())
                : unproven("exact PR remains in merge queue state "
                        + queue.entryState());
    }

    private EffectResult probeDeletedBranch(Action action)
            throws RetryableActionException
    {
        requireExactTarget(action);
        BranchTarget branch = branchTarget(action);
        Optional<String> head = pullRequests.fetchBranchHeadSha(
                branch.pat(), branch.repository(), action.branchName());
        if (head.isEmpty()) {
            return proven(
                    "remote-branch:absent:" + action.headRepositoryId()
                            + ":" + action.branchName(),
                    "exact remote branch is absent after " + action.headSha());
        }
        if (!action.headSha().equals(head.orElseThrow())) {
            throw new RetryableActionException(
                    "remote branch moved beyond the authorized PR head");
        }
        return unproven("exact remote branch still exists");
    }

    private EffectResult probeComment(Action action)
            throws ExecutionPorts.IndeterminateExecutionException,
            RetryableActionException
    {
        Target target = requireExactTarget(action);
        String viewer = viewer(target);
        String body = requireText(action.payload().body(), "comment body");
        Instant since = recoveryFloor(action.authorizedAt());
        List<PrTimelineEvent> matches = pullRequests.fetchPrIssueComments(
                        target.pat(), target.ref(), since)
                .stream()
                .filter(event -> event.githubId() != null)
                .filter(event -> "COMMENTED".equals(normalize(event.event())))
                .filter(event -> sameLogin(viewer, event.actor()))
                .filter(event -> body.equals(event.body()))
                .filter(event -> notBefore(
                        event.timestamp(), since))
                .filter(event -> !baseline(action).contains(
                        "issue-comment:" + event.githubId()))
                .toList();
        return unique(matches,
                event -> "issue-comment:" + event.githubId(),
                event -> "top-level comment " + event.githubId() + " on "
                        + action.headSha());
    }

    private EffectResult probeReview(Action action)
            throws ExecutionPorts.IndeterminateExecutionException,
            RetryableActionException
    {
        Target target = requireExactTarget(action);
        String viewer = viewer(target);
        String reviewAction = requireReviewAction(
                action.payload().reviewAction());
        String expectedState = expectedReviewState(reviewAction);
        String body = action.payload().body() == null
                ? "" : action.payload().body();
        Instant since = recoveryFloor(action.authorizedAt());
        List<PullRequestReview> matches = pullRequests.listReviews(
                        target.pat(), target.ref()).stream()
                .filter(review -> sameLogin(viewer, review.author()))
                .filter(review -> body.equals(nullToEmpty(review.body())))
                .filter(review -> expectedState.equals(normalize(review.state())))
                .filter(review -> action.headSha().equals(review.commitId()))
                .filter(review -> notBefore(
                        review.submittedAt(), since))
                .filter(review -> !baseline(action).contains(
                        "review:" + review.id()))
                .toList();
        if (matches.size() != 1) {
            if (matches.size() > 1) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "multiple matching reviews prevent exact recovery");
            }
            return unproven("no matching review is observable");
        }
        PullRequestReview review = matches.getFirst();
        List<PrReviewThreadMessage> actual = pullRequests.fetchPrReviewComments(
                        target.pat(), target.ref(), since)
                .stream()
                .filter(comment -> comment.reviewId() != null
                        && comment.reviewId() == review.id())
                .filter(comment -> comment.inReplyTo() == null)
                .toList();
        if (!lineMultiset(action.payload().drafts()).equals(
                actualLineMultiset(actual))) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "submitted review line comments differ from authorization");
        }
        return proven(
                "review:" + review.id(),
                "review " + review.id() + " " + reviewAction + " on "
                        + action.headSha());
    }

    private Target requireExactTarget(Action action)
            throws RetryableActionException
    {
        RepoRef repository = RepoRef.parse(action.remoteRepositoryId());
        PullRequestRef ref = PullRequestRef.of(
                repository.owner(), repository.repo(),
                action.pullRequestNumber());
        Target target = new Target(ref, pats.resolve(repository.fullName()));
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(target.pat(), target.ref()),
                "GitHub returned no pull request detail");
        if (!TargetPolicy.forAction(action.kind()).allows(detail)
                || !action.headSha().equals(detail.headSha())
                || !action.baseSha().equals(detail.baseSha())
                || !sameRepository(
                        action.headRepositoryId(), detail.headRepo())
                || !action.branchName().equals(detail.headRef())
                || !sameRepository(
                        action.remoteRepositoryId(), detail.baseRepo())) {
            throw new RetryableActionException(
                    "pull request moved outside the exact user authorization");
        }
        return target;
    }

    private BranchTarget branchTarget(Action action)
    {
        RepoRef repository = RepoRef.parse(action.headRepositoryId());
        return new BranchTarget(
                PullRequestRef.of(
                        repository.owner(), repository.repo(),
                        action.pullRequestNumber()),
                pats.resolve(repository.fullName()));
    }

    private String viewer(Target target)
    {
        String login = requireText(
                pullRequests.fetchUserProfile(target.pat()).login(),
                "GitHub viewer login");
        return login;
    }

    private EffectResult recover(
            Action action,
            ExecutionContext context,
            RuntimeException failure)
            throws Exception
    {
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
                "GitHub user remote action outcome is not independently proven",
                failure);
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

    private static EffectResult requireProven(
            EffectResult result, String message)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        if (!result.proven()) {
            throw new ExecutionPorts.IndeterminateExecutionException(message);
        }
        return result;
    }

    private static <T> EffectResult unique(
            List<T> matches,
            Function<T, String> identity,
            Function<T, String> evidence)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        if (matches.size() == 1) {
            T match = matches.getFirst();
            return proven(identity.apply(match), evidence.apply(match));
        }
        if (matches.size() > 1) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "multiple matching effects prevent exact recovery");
        }
        return unproven("no matching effect is observable");
    }

    private static EffectResult proven(String identity, String evidence)
    {
        return new EffectResult(true, identity, evidence);
    }

    private static EffectResult unproven(String evidence)
    {
        return new EffectResult(false, null, evidence);
    }

    private static void requireActive(ExecutionContext context)
            throws ExecutionPorts.OperationCanceledException
    {
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "V2 user remote action was canceled");
        }
    }

    private static boolean notBefore(Instant observed, Instant authorized)
    {
        return observed != null && !observed.isBefore(authorized);
    }

    private static Instant recoveryFloor(Instant authorized)
    {
        return authorized.truncatedTo(ChronoUnit.SECONDS);
    }

    private static List<String> baseline(Action action)
    {
        return requireNonNull(
                action.recoveryBaseline(),
                "V2 user remote action recovery baseline is missing");
    }

    private static boolean sameLogin(String left, String right)
    {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean sameRepository(String left, String right)
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

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
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

    private record BranchTarget(PullRequestRef repository, String pat) {}

    private enum TargetPolicy
    {
        OPEN,
        MERGED,
        OPEN_OR_TERMINAL;

        private static TargetPolicy forAction(
                UserRemoteActionOperationHandler.ActionKind kind)
        {
            return switch (kind) {
                case DEQUEUE, SUBMIT_REVIEW -> OPEN;
                case DELETE_REMOTE_BRANCH -> MERGED;
                case POST_TOP_LEVEL_COMMENT -> OPEN_OR_TERMINAL;
            };
        }

        private boolean allows(PrRawDetail detail)
        {
            boolean open = !detail.merged()
                    && "OPEN".equals(normalize(detail.state()));
            boolean terminal = detail.merged()
                    || "CLOSED".equals(normalize(detail.state()));
            return switch (this) {
                case OPEN -> open;
                case MERGED -> detail.merged();
                case OPEN_OR_TERMINAL -> open || terminal;
            };
        }
    }

    private record LineKey(
            String path,
            Integer line,
            String side,
            Integer startLine,
            String startSide,
            String body) {}
}
