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
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.developmentflow.execution.provisioning.GitRunnerProvisioningGit;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.RequestedReviewers;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestRepository.ReviewThreadMeta;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Exact GitHub adapter for one already user-authorized feedback effect. */
@Component
public final class GitHubRemoteFeedbackEffectGateway
        implements RemoteFeedbackEffectOperationHandler.EffectGateway
{
    private static final String ORIGIN = "origin";

    private final PullRequestRepository pullRequests;
    private final PatResolver pats;
    private final GitRunner git;
    private final GitRunnerProvisioningGit remotes;
    private final WorktreeWriterLeaseManager writers;

    public GitHubRemoteFeedbackEffectGateway(
            PullRequestRepository pullRequests,
            PatResolver pats,
            GitRunner git,
            GitRunnerProvisioningGit remotes,
            WorktreeWriterLeaseManager writers)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.git = requireNonNull(git, "git is null");
        this.remotes = requireNonNull(remotes, "remotes is null");
        this.writers = requireNonNull(writers, "writers is null");
    }

    @Override
    public RemoteFeedbackEffectOperationHandler.EffectResult execute(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        requireNonNull(effect, "effect is null");
        requireNonNull(context, "context is null");
        RemoteFeedbackEffectOperationHandler.EffectResult existing =
                probe(effect, context);
        if (existing.proven()) {
            return existing;
        }
        requireActive(context);
        return switch (effect.kind()) {
            case POST_INLINE_REPLY -> postInlineReply(effect, context);
            case POST_TOP_LEVEL_REPLY -> postIssueComment(effect, context, false);
            case SUBMIT_REVIEW -> submitReview(effect, context);
            case REQUEST_REVIEWER -> requestReviewer(effect, context);
            case POST_MAINTAINER_NUDGE -> postIssueComment(effect, context, true);
            case RESOLVE_THREAD -> resolveThread(effect, context);
            case PUSH_COMMITS -> push(effect, context);
        };
    }

    @Override
    public RemoteFeedbackEffectOperationHandler.EffectResult probe(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        requireNonNull(effect, "effect is null");
        requireNonNull(context, "context is null");
        requireActive(context);
        return switch (effect.kind()) {
            case POST_INLINE_REPLY -> probeInlineReply(effect, context);
            case POST_TOP_LEVEL_REPLY -> probeIssueComment(effect, context, false);
            case SUBMIT_REVIEW -> probeReview(effect, context);
            case REQUEST_REVIEWER -> probeReviewer(effect, context);
            case POST_MAINTAINER_NUDGE -> probeIssueComment(effect, context, true);
            case RESOLVE_THREAD -> probeResolvedThread(effect, context);
            case PUSH_COMMITS -> probePush(effect, context);
        };
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult postInlineReply(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        long rootCommentId = positiveLong(
                effect.targetCommentId(), "targetCommentId");
        try {
            requireActive(context);
            PrReviewThreadMessage created = requireNonNull(
                    pullRequests.replyToReviewComment(
                            target.pat(), target.pullRequest(), rootCommentId,
                            effect.payload()),
                    "GitHub returned no inline reply");
            if (created.githubId() < 1
                    || !effect.payload().equals(created.body())
                    || created.inReplyTo() == null
                    || created.inReplyTo() != rootCommentId) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "GitHub returned a non-exact inline reply");
            }
            return proven("review-comment:" + created.githubId(),
                    "inline reply " + created.githubId() + " to root "
                            + rootCommentId + " on " + effect.headSha());
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(effect, context, failure);
        }
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult postIssueComment(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context,
            boolean maintainerNudge)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        try {
            requireActive(context);
            PrTimelineEvent created = requireNonNull(
                    pullRequests.createIssueComment(
                            target.pat(), target.pullRequest(), effect.payload()),
                    "GitHub returned no issue comment");
            if (created.githubId() == null || created.githubId() < 1
                    || !effect.payload().equals(created.body())) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "GitHub returned a non-exact issue comment");
            }
            return proven("issue-comment:" + created.githubId(),
                    (maintainerNudge ? "maintainer nudge " : "top-level reply ")
                            + created.githubId() + " on " + effect.headSha());
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(effect, context, failure);
        }
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult submitReview(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        String action = requireReviewAction(effect.reviewAction());
        try {
            requireActive(context);
            PullRequestReview created = requireNonNull(
                    pullRequests.createReview(
                            target.pat(), target.pullRequest(),
                            new CreateReviewCommand(
                                    Optional.of(effect.headSha()),
                                    Optional.of(effect.payload()), action,
                                    List.of())),
                    "GitHub returned no review");
            if (created.id() < 1
                    || !effect.payload().equals(created.body())
                    || !expectedReviewState(action).equals(
                            normalize(created.state()))
                    || !effect.headSha().equals(created.commitId())) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "GitHub returned a non-exact review");
            }
            return proven("review:" + created.id(),
                    "review " + created.id() + " " + action + " on "
                            + effect.headSha());
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(effect, context, failure);
        }
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult requestReviewer(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        String reviewer = requireReviewer(effect);
        try {
            requireActive(context);
            pullRequests.requestReviewers(
                    target.pat(), target.pullRequest(),
                    new RequestReviewersCommand(List.of(reviewer), List.of()));
            return requireProven(
                    probeReviewer(effect, context),
                    "GitHub did not expose the exact requested reviewer");
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(effect, context, failure);
        }
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult resolveThread(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        String threadId = threadId(effect);
        requireThread(target, threadId);
        try {
            requireActive(context);
            pullRequests.resolveReviewThread(target.pat(), threadId);
            return requireProven(
                    probeResolvedThread(effect, context),
                    "GitHub did not expose the exact resolved thread");
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(effect, context, failure);
        }
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult push(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        requirePushTarget(effect, context, false);
        Path worktree = normalizedPath(effect.worktreePath());
        WorktreeWriterLeaseManager.Lease lease = writers.acquire(
                context, worktree.toString());
        try {
            return writers.authorizeMutation(context, lease).run(fence ->
                    pushUnderFence(effect, context, worktree, fence));
        }
        catch (GitMutationFailure failure) {
            if (failure.getCause()
                    instanceof ExecutionPorts.OperationCanceledException canceled) {
                throw canceled;
            }
            if (failure.getCause()
                    instanceof RemoteFeedbackEffectOperationHandler.RetryableEffectException retryable) {
                throw retryable;
            }
            boolean interrupted = failure.getCause()
                    instanceof InterruptedException;
            if (interrupted) {
                Thread.interrupted();
            }
            try {
                return recover(effect, context, failure);
            }
            finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        catch (RuntimeException failure) {
            return recover(effect, context, failure);
        }
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult pushUnderFence(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context,
            Path worktree,
            MutationFence fence)
    {
        requireExactFence(effect, worktree, fence);
        String pushedHead = requireText(effect.payload(), "payload");
        try {
            requireCleanWorktree(effect, worktree, pushedHead);
            requireOrigin(worktree, effect.headRepositoryId());
            Optional<String> before = git.remoteHeadSha(
                    worktree, ORIGIN, effect.headRef());
            if (before.filter(pushedHead::equals).isPresent()) {
                return proven(pushedHead,
                        "exact feedback head already present on origin/"
                                + effect.headRef());
            }
            if (before.filter(effect.headSha()::equals).isEmpty()) {
                throw new RemoteFeedbackEffectOperationHandler.RetryableEffectException(
                        "remote branch moved outside the feedback push authorization");
            }
            requireActive(context);
            git.push(worktree);
            Optional<String> after = git.remoteHeadSha(
                    worktree, ORIGIN, effect.headRef());
            if (after.filter(pushedHead::equals).isEmpty()) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "push returned without exposing its exact remote head");
            }
            return proven(pushedHead,
                    "pushed exact feedback head " + pushedHead + " to origin/"
                            + effect.headRef());
        }
        catch (RemoteFeedbackEffectOperationHandler.RetryableEffectException
                | ExecutionPorts.IndeterminateExecutionException
                | ExecutionPorts.OperationCanceledException failure) {
            throw new GitMutationFailure(failure);
        }
        catch (IOException | InterruptedException failure) {
            throw new GitMutationFailure(failure);
        }
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult probeInlineReply(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        String viewer = viewer(target, context);
        long root = positiveLong(effect.targetCommentId(), "targetCommentId");
        List<PrReviewThreadMessage> matches = pullRequests.fetchPrReviewComments(
                        target.pat(), target.pullRequest(), effect.authorizedAt())
                .stream()
                .filter(reply -> reply.inReplyTo() != null
                        && reply.inReplyTo() == root)
                .filter(reply -> sameLogin(viewer, reply.author()))
                .filter(reply -> effect.payload().equals(reply.body()))
                .filter(reply -> notBeforeAuthorization(
                        reply.createdAt(), effect.authorizedAt()))
                .toList();
        requireActive(context);
        return unique(matches, match -> "review-comment:" + match.githubId(),
                match -> "inline reply " + match.githubId() + " to root "
                        + root + " on " + effect.headSha());
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult probeIssueComment(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context,
            boolean maintainerNudge)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        String viewer = viewer(target, context);
        List<PrTimelineEvent> matches = pullRequests.fetchPrIssueComments(
                        target.pat(), target.pullRequest(), effect.authorizedAt())
                .stream()
                .filter(event -> event.githubId() != null)
                .filter(event -> "COMMENTED".equals(normalize(event.event())))
                .filter(event -> sameLogin(viewer, event.actor()))
                .filter(event -> effect.payload().equals(event.body()))
                .filter(event -> notBeforeAuthorization(
                        event.timestamp(), effect.authorizedAt()))
                .toList();
        requireActive(context);
        String prefix = maintainerNudge ? "maintainer nudge " : "top-level reply ";
        return unique(matches, match -> "issue-comment:" + match.githubId(),
                match -> prefix + match.githubId() + " on " + effect.headSha());
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult probeReview(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        String viewer = viewer(target, context);
        String action = requireReviewAction(effect.reviewAction());
        String expectedState = expectedReviewState(action);
        List<PullRequestReview> matches = pullRequests.listReviews(
                        target.pat(), target.pullRequest()).stream()
                .filter(review -> sameLogin(viewer, review.author()))
                .filter(review -> effect.payload().equals(review.body()))
                .filter(review -> expectedState.equals(normalize(review.state())))
                .filter(review -> effect.headSha().equals(review.commitId()))
                .filter(review -> notBeforeAuthorization(
                        review.submittedAt(), effect.authorizedAt()))
                .toList();
        requireActive(context);
        return unique(matches, match -> "review:" + match.id(),
                match -> "review " + match.id() + " " + action + " on "
                        + effect.headSha());
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult probeReviewer(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        String reviewer = requireReviewer(effect);
        requireActive(context);
        RequestedReviewers requested = requireNonNull(
                pullRequests.getRequestedReviewers(
                        target.pat(), target.pullRequest()),
                "GitHub returned no requested-reviewer state");
        requireActive(context);
        boolean present = requested.users().stream()
                .anyMatch(login -> sameLogin(login, reviewer));
        return present
                ? proven("requested-reviewer:" + reviewer.toLowerCase(Locale.ROOT),
                        "review requested from " + reviewer + " on "
                                + effect.headSha())
                : unproven("reviewer is not requested on the exact head");
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult probeResolvedThread(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(effect, context);
        String threadId = threadId(effect);
        requireActive(context);
        ReviewThreadMeta thread = requireThread(target, threadId);
        requireActive(context);
        return thread.resolved()
                ? proven("review-thread:" + threadId,
                        "resolved thread " + threadId + " on " + effect.headSha())
                : unproven("review thread remains open on the exact head");
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult probePush(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        PushTarget target = requirePushTarget(effect, context, true);
        Optional<String> remote = git.remoteHeadSha(
                target.worktree(), ORIGIN, effect.headRef());
        requireActive(context);
        if (remote.filter(target.pushedHead()::equals).isPresent()) {
            return proven(target.pushedHead(),
                    "exact feedback head present on origin/" + effect.headRef());
        }
        if (remote.isEmpty()
                || remote.filter(effect.headSha()::equals).isPresent()) {
            return unproven("feedback head is not present on the exact branch");
        }
        throw new RemoteFeedbackEffectOperationHandler.RetryableEffectException(
                "remote branch moved outside the feedback push authorization");
    }

    private PushTarget requirePushTarget(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context,
            boolean allowPushedHead)
            throws Exception
    {
        Path worktree = normalizedPath(effect.worktreePath());
        String pushedHead = requireText(effect.payload(), "payload");
        requireOrigin(worktree, effect.headRepositoryId());
        Target target = target(effect);
        requireActive(context);
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(target.pat(), target.pullRequest()),
                "GitHub returned no pull request detail");
        requireActive(context);
        boolean exactHead = effect.headSha().equals(detail.headSha())
                || allowPushedHead && pushedHead.equals(detail.headSha());
        if (!exactHead || !effect.baseSha().equals(detail.baseSha())
                || !"OPEN".equals(normalize(detail.state()))) {
            throw new RemoteFeedbackEffectOperationHandler.RetryableEffectException(
                    "pull request moved outside the feedback push authorization");
        }
        return new PushTarget(worktree, pushedHead);
    }

    private Target requireExactTarget(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context)
            throws Exception
    {
        Target target = target(effect);
        requireActive(context);
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(target.pat(), target.pullRequest()),
                "GitHub returned no pull request detail");
        requireActive(context);
        if (!effect.headSha().equals(detail.headSha())
                || !effect.baseSha().equals(detail.baseSha())
                || !"OPEN".equals(normalize(detail.state()))) {
            throw new RemoteFeedbackEffectOperationHandler.RetryableEffectException(
                    "pull request moved outside the exact feedback authorization");
        }
        return target;
    }

    private Target target(RemoteFeedbackEffectOperationHandler.Effect effect)
    {
        RepoRef repository = RepoRef.parse(effect.repositoryId());
        return new Target(
                PullRequestRef.of(repository.owner(), repository.repo(),
                        effect.pullRequestNumber()),
                pats.resolve(repository.fullName()));
    }

    private String viewer(Target target, ExecutionContext context)
            throws ExecutionPorts.OperationCanceledException
    {
        requireActive(context);
        String viewer = requireText(
                pullRequests.fetchUserProfile(target.pat()).login(),
                "GitHub viewer login");
        requireActive(context);
        return viewer;
    }

    private ReviewThreadMeta requireThread(Target target, String threadId)
            throws RemoteFeedbackEffectOperationHandler.RetryableEffectException
    {
        return pullRequests.fetchReviewThreadResolution(
                        target.pat(), target.pullRequest()).stream()
                .filter(thread -> threadId.equals(thread.graphqlNodeId()))
                .findFirst()
                .orElseThrow(() ->
                        new RemoteFeedbackEffectOperationHandler.RetryableEffectException(
                                "review thread is no longer observable"));
    }

    private void requireCleanWorktree(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            Path worktree,
            String pushedHead)
            throws IOException, InterruptedException,
            RemoteFeedbackEffectOperationHandler.RetryableEffectException
    {
        if (!Files.isDirectory(worktree) || !git.isGitWorkingTree(worktree)
                || !effect.headRef().equals(git.currentBranch(worktree))
                || !git.statusPorcelainZ(worktree).isEmpty()
                || !pushedHead.equals(git.headSha(worktree))) {
            throw new RemoteFeedbackEffectOperationHandler.RetryableEffectException(
                    "worktree differs from the exact feedback push authorization");
        }
    }

    private void requireOrigin(Path worktree, String repositoryId)
            throws RemoteFeedbackEffectOperationHandler.RetryableEffectException
    {
        if (!ORIGIN.equals(remotes.requireExactRemote(worktree, repositoryId))) {
            throw new RemoteFeedbackEffectOperationHandler.RetryableEffectException(
                    "exact feedback push target is not the origin remote");
        }
    }

    private static void requireExactFence(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            Path worktree,
            MutationFence fence)
    {
        Path fenced = normalizedPath(fence.worktreePath());
        if (!worktree.equals(fenced)
                || !effect.taskId().equals(fence.taskId())
                || !effect.operationId().equals(fence.operationId())
                || effect.taskEpoch() != fence.taskEpoch()) {
            throw new IllegalStateException(
                    "feedback push does not hold its exact writer fence");
        }
    }

    private RemoteFeedbackEffectOperationHandler.EffectResult recover(
            RemoteFeedbackEffectOperationHandler.Effect effect,
            ExecutionContext context,
            RuntimeException failure)
            throws Exception
    {
        try {
            RemoteFeedbackEffectOperationHandler.EffectResult recovered =
                    probe(effect, context);
            if (recovered.proven()) {
                return recovered;
            }
        }
        catch (Exception probeFailure) {
            failure.addSuppressed(probeFailure);
        }
        throw new ExecutionPorts.IndeterminateExecutionException(
                "GitHub feedback effect outcome is not independently proven",
                failure);
    }

    private static RemoteFeedbackEffectOperationHandler.EffectResult requireProven(
            RemoteFeedbackEffectOperationHandler.EffectResult result,
            String message)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        if (!result.proven()) {
            throw new ExecutionPorts.IndeterminateExecutionException(message);
        }
        return result;
    }

    private static <T> RemoteFeedbackEffectOperationHandler.EffectResult unique(
            List<T> matches,
            Function<T, String> identity,
            Function<T, String> evidence)
    {
        if (matches.size() == 1) {
            T match = matches.getFirst();
            return proven(identity.apply(match), evidence.apply(match));
        }
        return unproven(matches.isEmpty()
                ? "no matching effect is observable"
                : "multiple matching effects prevent exact recovery");
    }

    private static String requireReviewer(
            RemoteFeedbackEffectOperationHandler.Effect effect)
    {
        String target = requireText(effect.externalTarget(), "externalTarget");
        String payload = requireText(effect.payload(), "payload");
        if (!sameLogin(target, payload)) {
            throw new IllegalArgumentException(
                    "requested reviewer payload differs from its exact target");
        }
        return target;
    }

    private static String threadId(
            RemoteFeedbackEffectOperationHandler.Effect effect)
    {
        if (effect.targetThreadId() != null
                && !effect.targetThreadId().isBlank()) {
            return effect.targetThreadId();
        }
        return requireText(effect.externalTarget(), "externalTarget");
    }

    private static String requireReviewAction(String action)
    {
        String normalized = normalize(requireText(action, "reviewAction"));
        return switch (normalized) {
            case "COMMENT", "APPROVE", "REQUEST_CHANGES" -> normalized;
            default -> throw new IllegalArgumentException(
                    "unsupported exact review action " + action);
        };
    }

    private static String expectedReviewState(String action)
    {
        return switch (action) {
            case "COMMENT" -> "COMMENTED";
            case "APPROVE" -> "APPROVED";
            case "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
            default -> throw new IllegalArgumentException(
                    "unsupported exact review action " + action);
        };
    }

    private static boolean notBeforeAuthorization(
            Instant observed, Instant authorized)
    {
        return observed != null && !observed.isBefore(authorized);
    }

    private static boolean sameLogin(String left, String right)
    {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static long positiveLong(String value, String name)
    {
        try {
            long parsed = Long.parseLong(requireText(value, name));
            if (parsed < 1) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        }
        catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be numeric", failure);
        }
    }

    private static Path normalizedPath(String value)
    {
        return Path.of(requireText(value, "worktreePath"))
                .toAbsolutePath().normalize();
    }

    private static RemoteFeedbackEffectOperationHandler.EffectResult proven(
            String externalEffectId, String evidence)
    {
        return new RemoteFeedbackEffectOperationHandler.EffectResult(
                true, externalEffectId, evidence);
    }

    private static RemoteFeedbackEffectOperationHandler.EffectResult unproven(
            String evidence)
    {
        return new RemoteFeedbackEffectOperationHandler.EffectResult(
                false, null, evidence);
    }

    private static void requireActive(ExecutionContext context)
            throws ExecutionPorts.OperationCanceledException
    {
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Remote feedback effect was canceled");
        }
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record Target(PullRequestRef pullRequest, String pat) {}

    private record PushTarget(Path worktree, String pushedHead) {}

    private static final class GitMutationFailure
            extends RuntimeException
    {
        private GitMutationFailure(Exception cause)
        {
            super(cause);
        }
    }
}
