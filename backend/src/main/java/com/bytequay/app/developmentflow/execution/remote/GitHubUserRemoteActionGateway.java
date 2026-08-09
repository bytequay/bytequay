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
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.RetryableActionException;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CreateReviewCommand.ReviewLineComment;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.RequestedReviewers;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestRepository.ReviewThreadMeta;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final GitRunner git;
    private final WorktreeWriterLeaseManager writers;

    public GitHubUserRemoteActionGateway(
            PullRequestRepository pullRequests,
            PatResolver pats,
            GitRunner git,
            WorktreeWriterLeaseManager writers)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.git = requireNonNull(git, "git is null");
        this.writers = requireNonNull(writers, "writers is null");
    }

    @Override
    public List<String> captureBaseline(Action action, ExecutionContext context)
            throws Exception
    {
        requireNonNull(action, "action is null");
        requireActive(context);
        if (action.semanticAction()
                == UserRemoteActionOperationHandler.SemanticAction
                    .TRIGGER_CI_EMPTY_COMMIT) {
            TriggerState state = inspectCiTrigger(action);
            if (state.disposition() != TriggerDisposition.READY_CREATE
                    && state.disposition() != TriggerDisposition.PROVEN) {
                throw new RetryableActionException(
                        "empty-commit CI trigger baseline is not exact");
            }
            return List.of(
                    "local-head:" + state.localHead(),
                    "remote-head:" + state.remoteHead(),
                    "pr-head:" + state.prHead());
        }
        Target target = requireExactTarget(action);
        return switch (action.semanticAction()) {
            case POST_TOP_LEVEL_COMMENT, COMMENT_AND_CLOSE ->
                    pullRequests.fetchPrIssueComments(
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
            case REPLY_REVIEW_THREAD, CREATE_INLINE_COMMENT ->
                    reviewCommentIds(target, action);
            case RERUN_FAILED_CHECKS -> checkBaseline(target, action);
            case EDIT_ISSUE_COMMENT, DELETE_ISSUE_COMMENT,
                    REACT_ISSUE_COMMENT -> List.of(requireIssueComment(
                    target, targetId(action)).identity());
            case EDIT_REVIEW_COMMENT, DELETE_REVIEW_COMMENT,
                    REACT_REVIEW_COMMENT -> List.of(requireReviewComment(
                    target, targetId(action)).identity());
            case SET_THREAD_RESOLUTION -> List.of(threadState(target, action));
            case MERGE, ENABLE_AUTO_MERGE, DISABLE_AUTO_MERGE,
                    APPLY_SUGGESTION -> List.of();
            case DEQUEUE, DELETE_REMOTE_BRANCH, SET_DRAFT_STATE, UPDATE_TITLE,
                    UPDATE_BODY, CLOSE_PULL_REQUEST, ADD_REVIEWER,
                    REMOVE_REVIEWER, SET_ASSIGNEE, SET_LABEL,
                    REACT_PULL_REQUEST -> List.of();
            case TRIGGER_CI_EMPTY_COMMIT -> throw new AssertionError();
        };
    }

    @Override
    public EffectResult execute(Action action, ExecutionContext context)
            throws Exception
    {
        requireNonNull(action, "action is null");
        if (action.semanticAction()
                == UserRemoteActionOperationHandler.SemanticAction
                    .TRIGGER_CI_EMPTY_COMMIT) {
            return executeCiTrigger(action, context);
        }
        EffectResult existing = probe(action, context);
        if (existing.proven()) {
            return existing;
        }
        requireActive(context);
        return switch (action.semanticAction()) {
            case DEQUEUE -> dequeue(action, context);
            case DELETE_REMOTE_BRANCH -> deleteBranch(action, context);
            case POST_TOP_LEVEL_COMMENT -> postComment(action, context);
            case SUBMIT_REVIEW -> submitReview(action, context);
            case RERUN_FAILED_CHECKS -> rerunFailedChecks(action, context);
            case SET_DRAFT_STATE -> mutateAndProbe(action, context, target ->
                    pullRequests.setPullRequestDraft(
                            target.pat(), target.ref(), selected(action)));
            case UPDATE_TITLE -> mutateAndProbe(action, context, target ->
                    pullRequests.updatePullRequest(
                            target.pat(), target.ref(), updateTitle(action)));
            case UPDATE_BODY -> mutateAndProbe(action, context, target ->
                    pullRequests.updatePullRequest(
                            target.pat(), target.ref(), updateBody(action)));
            case CLOSE_PULL_REQUEST -> mutateAndProbe(action, context, target ->
                    pullRequests.updatePullRequest(
                            target.pat(), target.ref(),
                            UpdatePullRequestCommand.close()));
            case COMMENT_AND_CLOSE -> commentAndClose(action, context);
            case REPLY_REVIEW_THREAD -> replyReviewThread(action, context);
            case EDIT_ISSUE_COMMENT -> mutateAndProbe(action, context, target ->
                    pullRequests.editIssueComment(
                            target.pat(), target.ref().owner(),
                            target.ref().repo(), targetId(action),
                            requireText(action.payload().body(), "comment body")));
            case EDIT_REVIEW_COMMENT -> mutateAndProbe(action, context, target ->
                    pullRequests.editReviewComment(
                            target.pat(), target.ref().owner(),
                            target.ref().repo(), targetId(action),
                            requireText(action.payload().body(), "comment body")));
            case DELETE_ISSUE_COMMENT -> mutateAndProbe(action, context,
                    target -> pullRequests.deleteIssueComment(
                            target.pat(), target.ref().owner(),
                            target.ref().repo(), targetId(action)));
            case DELETE_REVIEW_COMMENT -> mutateAndProbe(action, context,
                    target -> pullRequests.deleteReviewComment(
                            target.pat(), target.ref().owner(),
                            target.ref().repo(), targetId(action)));
            case ADD_REVIEWER -> mutateReviewer(action, context, true);
            case REMOVE_REVIEWER -> mutateReviewer(action, context, false);
            case SET_ASSIGNEE -> mutateAndProbe(action, context, target ->
                    pullRequests.setPullRequestAssignee(
                            target.pat(), target.ref(), value(action),
                            selected(action)));
            case SET_LABEL -> mutateAndProbe(action, context, target ->
                    pullRequests.setPullRequestLabel(
                            target.pat(), target.ref(), value(action),
                            selected(action)));
            case CREATE_INLINE_COMMENT -> createInlineComment(action, context);
            case REACT_PULL_REQUEST, REACT_REVIEW_COMMENT,
                    REACT_ISSUE_COMMENT -> addReaction(action, context);
            case SET_THREAD_RESOLUTION -> setThreadResolution(action, context);
            case MERGE -> merge(action, context);
            case ENABLE_AUTO_MERGE -> mutateAndProbe(
                    action, context, target -> pullRequests.enableAutoMerge(
                            target.pat(), target.ref(), mergeMethod(action)));
            case DISABLE_AUTO_MERGE -> mutateAndProbe(
                    action, context, target -> pullRequests.disableAutoMerge(
                            target.pat(), target.ref()));
            case APPLY_SUGGESTION -> applySuggestion(action, context);
            case TRIGGER_CI_EMPTY_COMMIT -> throw new AssertionError();
        };
    }

    @Override
    public EffectResult probe(Action action, ExecutionContext context)
            throws Exception
    {
        requireNonNull(action, "action is null");
        if (action.semanticAction()
                == UserRemoteActionOperationHandler.SemanticAction
                    .TRIGGER_CI_EMPTY_COMMIT) {
            return probeCiTrigger(action, context);
        }
        return switch (action.semanticAction()) {
            case DEQUEUE -> probeDequeue(action);
            case DELETE_REMOTE_BRANCH -> probeDeletedBranch(action);
            case POST_TOP_LEVEL_COMMENT -> probeComment(action);
            case SUBMIT_REVIEW -> probeReview(action);
            case RERUN_FAILED_CHECKS -> probeRerun(action);
            case SET_DRAFT_STATE -> probeDraft(action);
            case UPDATE_TITLE -> probeTitle(action);
            case UPDATE_BODY -> probeBody(action);
            case CLOSE_PULL_REQUEST -> probeClosed(action);
            case COMMENT_AND_CLOSE -> probeCommentAndClose(action);
            case REPLY_REVIEW_THREAD -> probeReviewReply(action);
            case EDIT_ISSUE_COMMENT -> probeIssueCommentBody(action);
            case EDIT_REVIEW_COMMENT -> probeReviewCommentBody(action);
            case DELETE_ISSUE_COMMENT -> probeIssueCommentDeleted(action);
            case DELETE_REVIEW_COMMENT -> probeReviewCommentDeleted(action);
            case ADD_REVIEWER -> probeReviewer(action, true);
            case REMOVE_REVIEWER -> probeReviewer(action, false);
            case SET_ASSIGNEE -> probeAssignee(action);
            case SET_LABEL -> probeLabel(action);
            case CREATE_INLINE_COMMENT -> probeInlineComment(action);
            case REACT_PULL_REQUEST, REACT_REVIEW_COMMENT,
                    REACT_ISSUE_COMMENT -> unproven(
                    "GitHub reaction identity is not exposed by this repository API");
            case SET_THREAD_RESOLUTION -> probeThreadResolution(action);
            case MERGE -> probeMerge(action);
            case ENABLE_AUTO_MERGE -> probeAutoMerge(action, true);
            case DISABLE_AUTO_MERGE -> probeAutoMerge(action, false);
            case APPLY_SUGGESTION -> probeSuggestionApplied(action);
            case TRIGGER_CI_EMPTY_COMMIT -> throw new AssertionError();
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

    private EffectResult rerunFailedChecks(
            Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        if (action.attemptCount() > 1) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "an unproven CI rerun is never submitted a second time");
        }
        try {
            requireActive(context);
            int count = pullRequests.rerunFailedChecks(
                    target.pat(), RepoRef.parse(action.remoteRepositoryId()),
                    action.headSha());
            if (count < 1) {
                throw new RetryableActionException(
                        "GitHub found no failed workflow on the exact head");
            }
            return proven(
                    effectIdentity(action, "ci-rerun"),
                    "re-ran " + count + " failed workflow(s) on "
                            + action.headSha());
        }
        catch (ExecutionPorts.OperationCanceledException
                | RetryableActionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    /**
     * The push-driven CI fallback is a local Git mutation plus a remote push,
     * not a GitHub "rerun failed" request.  It therefore executes only while
     * the dispatcher owns both LOCAL_GIT/GITHUB capacity and this Task's
     * worktree-writer lease.  A stable commit subject makes a crash after the
     * commit but before the push safely resumable without creating a second
     * empty commit.
     */
    private EffectResult executeCiTrigger(
            Action action, ExecutionContext context)
            throws Exception
    {
        WorktreeWriterLeaseManager.Lease writer = writers.acquire(
                context, requireText(action.worktreePath(), "worktreePath"));
        TriggerState state = inspectCiTrigger(action);
        if (state.disposition() == TriggerDisposition.PROVEN) {
            return provenCiTrigger(action, state.generatedHead());
        }
        if (state.disposition() == TriggerDisposition.WAITING_FOR_PR_HEAD) {
            return unproven(
                    "empty commit is pushed; waiting for the PR head probe");
        }
        requireActive(context);
        try {
            return writers.authorizeMutation(context, writer).run(fence -> {
                requireCiTriggerFence(action, fence);
                try {
                    return mutateCiTrigger(action);
                }
                catch (IOException failure) {
                    throw new CiTriggerMutationFailure(failure);
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new CiTriggerMutationFailure(failure);
                }
                catch (RetryableActionException failure) {
                    throw new CiTriggerMutationFailure(failure);
                }
            });
        }
        catch (CiTriggerMutationFailure failure) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "empty-commit CI trigger stopped after its writer claim",
                    failure.getCause());
        }
    }

    private EffectResult probeCiTrigger(
            Action action, ExecutionContext context)
            throws Exception
    {
        writers.acquire(
                context, requireText(action.worktreePath(), "worktreePath"));
        TriggerState state = inspectCiTrigger(action);
        return state.disposition() == TriggerDisposition.PROVEN
                ? provenCiTrigger(action, state.generatedHead())
                : unproven(switch (state.disposition()) {
                    case READY_CREATE -> "authorized empty commit is not present";
                    case READY_PUSH -> "authorized empty commit is not pushed";
                    case WAITING_FOR_PR_HEAD ->
                            "empty commit is pushed; waiting for the PR head probe";
                    case PROVEN -> throw new AssertionError();
                });
    }

    private EffectResult mutateCiTrigger(Action action)
            throws IOException, InterruptedException, RetryableActionException
    {
        TriggerState state = inspectCiTrigger(action);
        if (state.disposition() == TriggerDisposition.PROVEN) {
            return provenCiTrigger(action, state.generatedHead());
        }
        if (state.disposition() == TriggerDisposition.WAITING_FOR_PR_HEAD) {
            return unproven(
                    "empty commit is pushed; waiting for the PR head probe");
        }
        Path worktree = worktree(action);
        if (state.disposition() == TriggerDisposition.READY_CREATE) {
            String created = git.commitEmpty(worktree, ciTriggerSubject(action));
            requireGeneratedCommit(action, worktree, created);
        }
        git.push(worktree);
        TriggerState result = inspectCiTrigger(action);
        return result.disposition() == TriggerDisposition.PROVEN
                ? provenCiTrigger(action, result.generatedHead())
                : unproven("empty commit push is awaiting exact remote proof");
    }

    private TriggerState inspectCiTrigger(Action action)
            throws IOException, InterruptedException, RetryableActionException
    {
        Path worktree = worktree(action);
        String branch = git.currentBranch(worktree);
        if (!action.branchName().equals(branch)) {
            throw new RetryableActionException(
                    "worktree is not on the authorized PR branch");
        }
        RepoRef origin = git.remoteSlug(worktree, "origin")
                .orElseThrow(() -> new RetryableActionException(
                        "worktree origin has no exact repository identity"));
        if (!sameRepository(action.headRepositoryId(), origin.fullName())) {
            throw new RetryableActionException(
                    "worktree origin differs from the authorized head repository");
        }

        String localHead = git.headSha(worktree);
        String remoteHead = git.remoteHeadSha(
                        worktree, "origin", action.branchName())
                .orElseThrow(() -> new RetryableActionException(
                        "authorized remote branch is missing"));
        PrRawDetail detail = requireCiTriggerTarget(action);
        String prHead = requireText(detail.headSha(), "PR head SHA");

        LinkedHashSet<String> generated = new LinkedHashSet<>();
        for (String head : List.of(localHead, remoteHead, prHead)) {
            if (!action.headSha().equals(head)) {
                generated.add(head);
            }
        }
        if (generated.isEmpty()) {
            return new TriggerState(
                    TriggerDisposition.READY_CREATE, localHead, remoteHead,
                    prHead, null);
        }
        if (generated.size() != 1) {
            throw new RetryableActionException(
                    "local, remote, and PR heads diverged during CI trigger");
        }
        String generatedHead = generated.getFirst();
        requireGeneratedCommit(action, worktree, generatedHead);
        if (!localHead.equals(generatedHead)) {
            throw new RetryableActionException(
                    "worktree no longer contains the exact generated CI commit");
        }
        TriggerDisposition disposition;
        if (remoteHead.equals(action.headSha())
                && prHead.equals(action.headSha())) {
            disposition = TriggerDisposition.READY_PUSH;
        }
        else if (remoteHead.equals(generatedHead)
                && prHead.equals(action.headSha())) {
            disposition = TriggerDisposition.WAITING_FOR_PR_HEAD;
        }
        else if (remoteHead.equals(generatedHead)
                && prHead.equals(generatedHead)) {
            disposition = TriggerDisposition.PROVEN;
        }
        else {
            throw new RetryableActionException(
                    "empty-commit CI trigger has a non-recoverable head split");
        }
        return new TriggerState(
                disposition, localHead, remoteHead, prHead, generatedHead);
    }

    private PrRawDetail requireCiTriggerTarget(Action action)
            throws RetryableActionException
    {
        RepoRef repository = RepoRef.parse(action.remoteRepositoryId());
        PullRequestRef ref = PullRequestRef.of(
                repository.owner(), repository.repo(),
                action.pullRequestNumber());
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(
                        pats.resolve(repository.fullName()), ref),
                "GitHub returned no pull request detail");
        if (!TargetPolicy.OPEN.allows(detail)
                || !action.baseSha().equals(detail.baseSha())
                || !sameRepository(action.headRepositoryId(), detail.headRepo())
                || !action.branchName().equals(detail.headRef())
                || !sameRepository(
                        action.remoteRepositoryId(), detail.baseRepo())) {
            throw new RetryableActionException(
                    "pull request moved outside the empty-commit authorization");
        }
        return detail;
    }

    private void requireGeneratedCommit(
            Action action, Path worktree, String generatedHead)
            throws IOException, InterruptedException, RetryableActionException
    {
        Optional<String> resolved = git.resolveCommitSha(worktree, generatedHead);
        Optional<String> parent = git.resolveCommitSha(
                worktree, generatedHead + "^");
        List<GitRunner.CommitEntry> commits = git.listCommits(
                worktree, generatedHead, 1);
        if (resolved.isEmpty() || !generatedHead.equals(resolved.orElseThrow())
                || parent.isEmpty()
                || !action.headSha().equals(parent.orElseThrow())
                || commits.size() != 1
                || !generatedHead.equals(commits.getFirst().sha())
                || !ciTriggerSubject(action).equals(
                        commits.getFirst().subject())
                || !git.diff(
                        worktree, action.headSha(), generatedHead, 1024)
                    .isBlank()) {
            throw new RetryableActionException(
                    "generated CI commit is not the exact empty child of the authorized head");
        }
    }

    private static EffectResult provenCiTrigger(
            Action action, String generatedHead)
    {
        requireText(generatedHead, "generatedHead");
        return proven(
                UserRemoteActionOperationHandler.CI_TRIGGER_EFFECT_PREFIX
                        + generatedHead,
                "pushed exact empty CI commit " + generatedHead
                        + " over " + action.headSha());
    }

    private static String ciTriggerSubject(Action action)
    {
        return "Re-trigger CI [bytequay:" + action.operationId() + "]";
    }

    private static Path worktree(Action action)
    {
        return Path.of(requireText(action.worktreePath(), "worktreePath"))
                .toAbsolutePath().normalize();
    }

    private static void requireCiTriggerFence(
            Action action,
            WorktreeWriterLeaseManager.MutationFence fence)
    {
        requireNonNull(fence, "fence is null");
        if (!worktree(action).equals(
                    Path.of(fence.worktreePath()).toAbsolutePath().normalize())
                || !action.taskId().equals(fence.taskId())
                || !action.operationId().equals(fence.operationId())
                || action.taskEpoch() != fence.taskEpoch()) {
            throw new IllegalStateException(
                    "empty-commit CI mutation fence differs from its Task worktree");
        }
    }

    private EffectResult replyReviewThread(
            Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        long root = targetId(action);
        String body = requireText(action.payload().body(), "reply body");
        try {
            requireActive(context);
            PrReviewThreadMessage created = requireNonNull(
                    pullRequests.replyToReviewComment(
                            target.pat(), target.ref(), root, body),
                    "GitHub returned no review-thread reply");
            if (created.githubId() < 1 || !body.equals(created.body())
                    || created.inReplyTo() == null
                    || created.inReplyTo() != root) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "GitHub returned a non-exact review-thread reply");
            }
            return proven("review-comment:" + created.githubId(),
                    "review-thread reply " + created.githubId() + " on "
                            + action.headSha());
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult commentAndClose(
            Action action, ExecutionContext context)
            throws Exception
    {
        EffectResult comment = probeComment(action);
        if (!comment.proven()) {
            comment = postComment(action, context);
        }
        EffectResult closed = probeClosed(action);
        if (!closed.proven()) {
            Target target = requireExactTarget(action);
            try {
                // The irreversible comment step is now proven. Completing
                // its ordered close step is recovery, even if the enclosing
                // Task was canceled between those two remote calls.
                pullRequests.updatePullRequest(
                        target.pat(), target.ref(),
                        UpdatePullRequestCommand.close());
            }
            catch (RuntimeException failure) {
                return recover(action, context, failure);
            }
        }
        return requireProven(
                probeCommentAndClose(action),
                "GitHub did not expose both the exact comment and closed PR");
    }

    private EffectResult mutateReviewer(
            Action action, ExecutionContext context, boolean add)
            throws Exception
    {
        return mutateAndProbe(action, context, target -> {
            RequestReviewersCommand command = new RequestReviewersCommand(
                    List.of(value(action)), List.of());
            if (add) {
                pullRequests.requestReviewers(
                        target.pat(), target.ref(), command);
            }
            else {
                pullRequests.removeRequestedReviewers(
                        target.pat(), target.ref(), command);
            }
        });
    }

    private EffectResult createInlineComment(
            Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        ActionPayload payload = action.payload();
        try {
            requireActive(context);
            pullRequests.createInlineReviewComment(
                    target.pat(), target.ref(),
                    requireText(payload.body(), "comment body"),
                    requireText(payload.filePath(), "filePath"),
                    requireNonNull(payload.lineNumber(), "lineNumber is null"),
                    requireText(payload.side(), "side"), action.headSha(),
                    payload.startLine(), payload.startSide());
            return requireProven(
                    probeInlineComment(action),
                    "GitHub did not expose the exact inline comment");
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    /**
     * Commits a review suggestion over the lines it was written against,
     * the same edit "Apply suggestion" makes on github.com.
     *
     * <p>The line range is only meaningful at the head the reviewer
     * commented on, so {@link #requireExactTarget} pins it; the blob sha
     * read here pins the file itself, so a push that landed between the
     * two calls makes GitHub reject the write rather than let a stale
     * range overwrite it. The write moves the head, which is why the proof
     * reads the branch content back instead of re-pinning the target.
     */
    private EffectResult applySuggestion(
            Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        ActionPayload payload = action.payload();
        String path = requireText(payload.filePath(), "filePath");
        int endLine = requireNonNull(payload.lineNumber(), "lineNumber is null");
        int startLine = payload.startLine() == null
                ? endLine : payload.startLine();
        try {
            requireActive(context);
            RepoRef head = headRepository(action);
            PullRequestRepository.FileBlob blob = pullRequests
                    .fetchFileBlob(target.pat(), head, path, action.branchName())
                    .orElseThrow(() -> new IllegalStateException(
                            "suggestion target " + path
                                    + " does not exist on " + action.branchName()));
            String patched = SuggestionPatch.apply(
                    blob.text(), startLine, endLine, payload.body());
            if (patched.equals(blob.text())) {
                // The suggestion is already the file's content — nothing to
                // commit, and GitHub would reject an empty write anyway.
                return proven(suggestionEffectId(action),
                        "suggestion already present in " + path);
            }
            pullRequests.commitFileText(
                    target.pat(), head, path, action.branchName(), blob.sha(),
                    patched, suggestionCommitMessage(action, path));
            return requireProven(
                    probeSuggestionApplied(action),
                    "GitHub did not expose the applied suggestion");
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult probeSuggestionApplied(Action action)
            throws RetryableActionException
    {
        ActionPayload payload = action.payload();
        String path = requireText(payload.filePath(), "filePath");
        int endLine = requireNonNull(payload.lineNumber(), "lineNumber is null");
        int startLine = payload.startLine() == null
                ? endLine : payload.startLine();
        RepoRef head = headRepository(action);
        String pat = pats.resolve(head.fullName());
        return pullRequests.fetchFileBlob(pat, head, path, action.branchName())
                .filter(blob -> SuggestionPatch.applied(
                        blob.text(), startLine, payload.body()))
                .map(blob -> proven(suggestionEffectId(action),
                        "suggestion is present at " + path + ":" + startLine
                                + " on " + action.branchName()))
                .orElseGet(() -> unproven(
                        "suggestion is not present at " + path + ":" + startLine));
    }

    private static RepoRef headRepository(Action action)
    {
        // Fork PRs commit to the contributor's branch, not the base repo.
        return RepoRef.parse(requireText(
                action.headRepositoryId(), "headRepositoryId"));
    }

    private static String suggestionEffectId(Action action)
    {
        return "suggestion:" + action.operationId();
    }

    private static String suggestionCommitMessage(Action action, String path)
    {
        return "Apply suggestion to " + path + "\n\n"
                + "Co-authored-by review suggestion applied from "
                + action.remoteRepositoryId() + "#"
                + action.pullRequestNumber();
    }

    private EffectResult addReaction(
            Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        String content = value(action);
        String viewer = viewer(target);
        try {
            requireActive(context);
            switch (action.semanticAction()) {
                case REACT_PULL_REQUEST -> pullRequests.addPullRequestReaction(
                        target.pat(), target.ref(), content);
                case REACT_REVIEW_COMMENT ->
                        pullRequests.addReviewCommentReaction(
                                target.pat(), target.ref().owner(),
                                target.ref().repo(), targetId(action), content);
                case REACT_ISSUE_COMMENT ->
                        pullRequests.addIssueCommentReaction(
                                target.pat(), target.ref().owner(),
                                target.ref().repo(), targetId(action), content);
                default -> throw new IllegalArgumentException(
                        "action is not a reaction");
            }
            // GitHub's reaction creation endpoints are idempotent for the
            // viewer/target/content tuple. A normal response proves that
            // tuple; an unknown response remains indeterminate and is safe to
            // replay because the upstream operation itself is idempotent.
            return proven(
                    "reaction:" + viewer.toLowerCase(Locale.ROOT) + ":"
                            + action.semanticAction().name().toLowerCase(
                            Locale.ROOT) + ":"
                            + nullToEmpty(action.payload().targetId()) + ":"
                            + content,
                    "GitHub accepted the exact idempotent reaction tuple");
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult setThreadResolution(
            Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        ReviewThreadMeta thread = requireThread(target, targetId(action));
        try {
            requireActive(context);
            if (selected(action)) {
                pullRequests.resolveReviewThread(
                        target.pat(), thread.graphqlNodeId());
            }
            else {
                pullRequests.unresolveReviewThread(
                        target.pat(), thread.graphqlNodeId());
            }
            return requireProven(
                    probeThreadResolution(action),
                    "GitHub did not expose the exact thread resolution");
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult merge(Action action, ExecutionContext context)
            throws Exception
    {
        Target target = requireExactTarget(action);
        EffectResult existing = probeMerge(action);
        if (existing.proven()) {
            return existing;
        }
        try {
            requireActive(context);
            Optional<PullRequestRepository.MergeQueueProbe> queue =
                    pullRequests.probeMergeQueue(target.pat(), target.ref());
            MergeResult result;
            if (queue.isPresent()) {
                result = pullRequests.enqueuePullRequest(
                        target.pat(), queue.orElseThrow().pullRequestNodeId(),
                        action.headSha());
            }
            else {
                result = pullRequests.mergePullRequest(
                        target.pat(), target.ref(), new MergePullRequestCommand(
                                mergeMethod(action).toLowerCase(Locale.ROOT),
                                Optional.empty(), Optional.empty(),
                                Optional.of(action.headSha())));
            }
            if (result == null || (!result.merged() && !result.queued())) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "GitHub did not accept the exact merge command");
            }
            return requireProven(probeMerge(action),
                    "GitHub did not expose the merge or exact queue entry");
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            return recover(action, context, failure);
        }
    }

    private EffectResult probeMerge(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        if (pullRequests.isPullRequestMerged(target.pat(), target.ref())) {
            return proven(effectIdentity(action, "merged"),
                    "the exact pull request is merged");
        }
        PullRequestRepository.MergeQueueInfo queue =
                pullRequests.fetchMergeQueueInfo(target.pat(), target.ref());
        return queue != null && queue.entryState() != null
                && !queue.entryState().isBlank()
                ? proven(effectIdentity(action, "merge-queue"),
                        "the exact pull request has a merge-queue entry")
                : unproven("the exact pull request is neither merged nor queued");
    }

    private EffectResult probeAutoMerge(Action action, boolean enabled)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        Optional<PullRequestRepository.AutoMergeStatus> status =
                pullRequests.fetchAutoMergeStatus(target.pat(), target.ref());
        boolean exact = enabled
                ? status.map(value -> mergeMethod(action).equalsIgnoreCase(
                        value.mergeMethod())).orElse(false)
                : status.isEmpty();
        return exact
                ? proven(effectIdentity(action,
                        "auto-merge:" + enabled + ":"
                                + (enabled ? mergeMethod(action) : "none")),
                        "auto-merge state matches the authorization")
                : unproven("auto-merge state differs from the authorization");
    }

    private static String mergeMethod(Action action)
    {
        String method = requireText(action.payload().value(), "merge method")
                .toUpperCase(Locale.ROOT);
        if (!List.of("MERGE", "SQUASH", "REBASE").contains(method)) {
            throw new IllegalArgumentException("merge method is invalid");
        }
        return method;
    }

    private EffectResult mutateAndProbe(
            Action action, ExecutionContext context, Mutation mutation)
            throws Exception
    {
        Target target = requireExactTarget(action);
        try {
            requireActive(context);
            mutation.apply(target);
            return requireProven(
                    probe(action, context),
                    "GitHub did not expose the exact requested state");
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
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

    private EffectResult probeRerun(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        Set<String> before = ImmutableSet.copyOf(baseline(action));
        List<PrCheckRunState> checks = pullRequests.fetchPrCheckRunsStrict(
                target.pat(), target.ref().owner(), target.ref().repo(),
                action.headSha());
        boolean restarted = checks.stream().anyMatch(check -> {
            String status = normalize(check.status());
            boolean active = "QUEUED".equals(status)
                    || "IN_PROGRESS".equals(status)
                    || "PENDING".equals(status);
            boolean passed = "COMPLETED".equals(status)
                    && successfulConclusion(check.conclusion());
            return (active || passed)
                    && (before.contains(checkId(check))
                    || before.contains(checkName(check)));
        });
        return restarted
                ? proven(effectIdentity(action, "ci-rerun"),
                        "a previously failed exact-head check is now active or successful")
                : unproven("no restarted failed check is independently observable");
    }

    private EffectResult probeDraft(Action action)
            throws RetryableActionException
    {
        PrRawDetail detail = exactDetail(action);
        boolean desired = selected(action);
        return detail.draft() == desired
                ? proven(effectIdentity(action, "draft:" + desired),
                        "exact PR draft state is " + desired)
                : unproven("exact PR draft state differs from the request");
    }

    private EffectResult probeTitle(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        String desired = value(action);
        String actual = pullRequests.fetchPrTitle(target.pat(), target.ref());
        return desired.equals(actual)
                ? proven(effectIdentity(action, "title"),
                        "exact PR title matches the authorized value")
                : unproven("exact PR title differs from the authorized value");
    }

    private EffectResult probeBody(Action action)
            throws RetryableActionException
    {
        String desired = requireNonNull(action.payload().body(), "body is null");
        return desired.equals(nullToEmpty(exactDetail(action).body()))
                ? proven(effectIdentity(action, "body"),
                        "exact PR body matches the authorized value")
                : unproven("exact PR body differs from the authorized value");
    }

    private EffectResult probeClosed(Action action)
            throws RetryableActionException
    {
        PrRawDetail detail = exactDetail(action);
        return !detail.merged() && "CLOSED".equals(normalize(detail.state()))
                ? proven(effectIdentity(action, "closed"),
                        "exact PR is closed without merge")
                : unproven("exact PR is not closed without merge");
    }

    private EffectResult probeCommentAndClose(Action action)
            throws Exception
    {
        EffectResult comment = probeComment(action);
        EffectResult closed = probeClosed(action);
        if (!comment.proven() || !closed.proven()) {
            return unproven("exact post-baseline comment and closed PR are not both observable");
        }
        return proven(
                "comment-and-close:" + comment.externalEffectId(),
                comment.evidence() + "; exact PR is closed without merge");
    }

    private EffectResult probeReviewReply(Action action)
            throws Exception
    {
        Target target = requireExactTarget(action);
        long root = targetId(action);
        String body = requireText(action.payload().body(), "reply body");
        String viewer = viewer(target);
        Instant since = recoveryFloor(action.authorizedAt());
        List<PrReviewThreadMessage> matches = pullRequests.fetchPrReviewComments(
                        target.pat(), target.ref(), since).stream()
                .filter(comment -> comment.githubId() > 0)
                .filter(comment -> comment.inReplyTo() != null
                        && comment.inReplyTo() == root)
                .filter(comment -> sameLogin(viewer, comment.author()))
                .filter(comment -> body.equals(comment.body()))
                .filter(comment -> notBefore(comment.createdAt(), since))
                .filter(comment -> !baseline(action).contains(
                        "review-comment:" + comment.githubId()))
                .toList();
        return unique(matches,
                comment -> "review-comment:" + comment.githubId(),
                comment -> "review-thread reply " + comment.githubId()
                        + " on " + action.headSha());
    }

    private EffectResult probeIssueCommentBody(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        Optional<ObservedComment> comment = issueComment(
                target, targetId(action));
        return comment.filter(found -> requireText(
                        action.payload().body(), "comment body")
                        .equals(found.body()))
                .map(found -> proven(found.identity(),
                        "issue comment body matches the authorized value"))
                .orElseGet(() -> unproven(
                        "issue comment body does not match the authorized value"));
    }

    private EffectResult probeReviewCommentBody(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        Optional<ObservedComment> comment = reviewComment(
                target, targetId(action));
        return comment.filter(found -> requireText(
                        action.payload().body(), "comment body")
                        .equals(found.body()))
                .map(found -> proven(found.identity(),
                        "review comment body matches the authorized value"))
                .orElseGet(() -> unproven(
                        "review comment body does not match the authorized value"));
    }

    private EffectResult probeIssueCommentDeleted(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        long id = targetId(action);
        return issueComment(target, id).isEmpty()
                ? proven("issue-comment:absent:" + id,
                        "exact issue comment is absent")
                : unproven("exact issue comment still exists");
    }

    private EffectResult probeReviewCommentDeleted(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        long id = targetId(action);
        return reviewComment(target, id).isEmpty()
                ? proven("review-comment:absent:" + id,
                        "exact review comment is absent")
                : unproven("exact review comment still exists");
    }

    private EffectResult probeReviewer(Action action, boolean selected)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        RequestedReviewers reviewers = requireNonNull(
                pullRequests.getRequestedReviewers(
                        target.pat(), target.ref()),
                "GitHub returned no requested reviewers");
        boolean present = nullToEmpty(reviewers.users()).stream()
                .anyMatch(login -> sameLogin(value(action), login));
        return present == selected
                ? proven(effectIdentity(action,
                        "reviewer:" + value(action).toLowerCase(Locale.ROOT)
                                + ":" + selected),
                        "requested reviewer state matches the authorization")
                : unproven("requested reviewer state differs from authorization");
    }

    private EffectResult probeAssignee(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        String login = value(action);
        Optional<PrTimelineEvent> latest = pullRequests.fetchPrTimeline(
                        target.pat(), target.ref(), null).stream()
                .filter(event -> sameLogin(login, event.assigneeLogin()))
                .filter(event -> "ASSIGNED".equals(normalize(event.event()))
                        || "UNASSIGNED".equals(normalize(event.event())))
                .max(Comparator.comparing(
                        PrTimelineEvent::timestamp,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        boolean present = latest
                .map(event -> "ASSIGNED".equals(normalize(event.event())))
                .orElse(false);
        return present == selected(action)
                ? proven(effectIdentity(action,
                        "assignee:" + login.toLowerCase(Locale.ROOT) + ":"
                                + selected(action)),
                        "assignee state matches the authorization")
                : unproven("assignee state differs from authorization");
    }

    private EffectResult probeLabel(Action action)
            throws RetryableActionException
    {
        String label = value(action);
        boolean present = nullToEmpty(exactDetail(action).labels()).stream()
                .anyMatch(actual -> actual.equalsIgnoreCase(label));
        return present == selected(action)
                ? proven(effectIdentity(action,
                        "label:" + label.toLowerCase(Locale.ROOT) + ":"
                                + selected(action)),
                        "label state matches the authorization")
                : unproven("label state differs from authorization");
    }

    private EffectResult probeInlineComment(Action action)
            throws Exception
    {
        Target target = requireExactTarget(action);
        ActionPayload payload = action.payload();
        String viewer = viewer(target);
        Instant since = recoveryFloor(action.authorizedAt());
        List<PrReviewThreadMessage> matches = pullRequests.fetchPrReviewComments(
                        target.pat(), target.ref(), since).stream()
                .filter(comment -> comment.githubId() > 0
                        && comment.inReplyTo() == null)
                .filter(comment -> sameLogin(viewer, comment.author()))
                .filter(comment -> action.headSha().equals(comment.commitId()))
                .filter(comment -> payload.body().equals(comment.body()))
                .filter(comment -> payload.filePath().equals(comment.filePath()))
                .filter(comment -> Objects.equals(
                        payload.lineNumber(), comment.lineNumber()))
                .filter(comment -> normalize(payload.side()).equals(
                        normalize(comment.side())))
                .filter(comment -> Objects.equals(
                        payload.startLine(), comment.startLine()))
                .filter(comment -> Objects.equals(
                        normalizeNullable(payload.startSide()),
                        normalizeNullable(comment.startSide())))
                .filter(comment -> notBefore(comment.createdAt(), since))
                .filter(comment -> !baseline(action).contains(
                        "review-comment:" + comment.githubId()))
                .toList();
        return unique(matches,
                comment -> "review-comment:" + comment.githubId(),
                comment -> "inline comment " + comment.githubId() + " on "
                        + action.headSha());
    }

    private EffectResult probeThreadResolution(Action action)
            throws RetryableActionException
    {
        Target target = requireExactTarget(action);
        ReviewThreadMeta thread = requireThread(target, targetId(action));
        return thread.resolved() == selected(action)
                ? proven("review-thread:" + thread.graphqlNodeId() + ":"
                                + selected(action),
                        "review-thread resolution matches the authorization")
                : unproven("review-thread resolution differs from authorization");
    }

    private Target requireExactTarget(Action action)
            throws RetryableActionException
    {
        RepoRef repository = RepoRef.parse(action.remoteRepositoryId());
        PullRequestRef ref = PullRequestRef.of(
                repository.owner(), repository.repo(),
                action.pullRequestNumber());
        String pat = pats.resolve(repository.fullName());
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(pat, ref),
                "GitHub returned no pull request detail");
        if (!TargetPolicy.forAction(action.semanticAction()).allows(detail)
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
        return new Target(ref, pat, detail);
    }

    private PrRawDetail exactDetail(Action action)
            throws RetryableActionException
    {
        return requireExactTarget(action).detail();
    }

    private List<String> reviewCommentIds(Target target, Action action)
    {
        return pullRequests.fetchPrReviewComments(
                        target.pat(), target.ref(),
                        recoveryFloor(action.authorizedAt())).stream()
                .filter(comment -> comment.githubId() > 0)
                .map(comment -> "review-comment:" + comment.githubId())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> checkBaseline(Target target, Action action)
    {
        return pullRequests.fetchPrCheckRunsStrict(
                        target.pat(), target.ref().owner(), target.ref().repo(),
                        action.headSha()).stream()
                .filter(GitHubUserRemoteActionGateway::failedCheck)
                .flatMap(check -> List.of(
                        checkId(check), checkName(check)).stream())
                .distinct()
                .sorted()
                .toList();
    }

    private ObservedComment requireIssueComment(Target target, long id)
            throws RetryableActionException
    {
        return issueComment(target, id).orElseThrow(() ->
                new RetryableActionException(
                        "exact issue comment target is missing"));
    }

    private ObservedComment requireReviewComment(Target target, long id)
            throws RetryableActionException
    {
        return reviewComment(target, id).orElseThrow(() ->
                new RetryableActionException(
                        "exact review comment target is missing"));
    }

    private Optional<ObservedComment> issueComment(Target target, long id)
    {
        return pullRequests.fetchPrIssueComments(
                        target.pat(), target.ref(), null).stream()
                .filter(event -> event.githubId() != null
                        && event.githubId() == id)
                .map(event -> new ObservedComment(
                        "issue-comment:" + id, event.body()))
                .findFirst();
    }

    private Optional<ObservedComment> reviewComment(Target target, long id)
    {
        return pullRequests.fetchPrReviewComments(
                        target.pat(), target.ref(), null).stream()
                .filter(comment -> comment.githubId() == id)
                .map(comment -> new ObservedComment(
                        "review-comment:" + id, comment.body()))
                .findFirst();
    }

    private String threadState(Target target, Action action)
            throws RetryableActionException
    {
        ReviewThreadMeta thread = requireThread(target, targetId(action));
        return "review-thread:" + thread.graphqlNodeId() + ":"
                + thread.resolved();
    }

    private ReviewThreadMeta requireThread(Target target, long rootCommentId)
            throws RetryableActionException
    {
        return pullRequests.fetchReviewThreadResolution(
                        target.pat(), target.ref()).stream()
                .filter(thread -> thread.rootCommentDatabaseId()
                        == rootCommentId)
                .filter(thread -> thread.graphqlNodeId() != null
                        && !thread.graphqlNodeId().isBlank())
                .findFirst()
                .orElseThrow(() -> new RetryableActionException(
                        "exact review thread target is missing"));
    }

    private static UpdatePullRequestCommand updateTitle(Action action)
    {
        return new UpdatePullRequestCommand(
                Optional.of(value(action)), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static UpdatePullRequestCommand updateBody(Action action)
    {
        return new UpdatePullRequestCommand(
                Optional.empty(), Optional.of(requireNonNull(
                        action.payload().body(), "body is null")),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static long targetId(Action action)
    {
        try {
            long value = Long.parseLong(requireText(
                    action.payload().targetId(), "targetId"));
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        }
        catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "targetId must be positive", failure);
        }
    }

    private static String value(Action action)
    {
        return requireText(action.payload().value(), "value");
    }

    private static boolean selected(Action action)
    {
        return requireNonNull(
                action.payload().selected(), "selected is null");
    }

    private static String effectIdentity(Action action, String suffix)
    {
        return action.semanticAction().name().toLowerCase(Locale.ROOT) + ":"
                + action.remoteRepositoryId() + "#"
                + action.pullRequestNumber() + ":" + action.headSha() + ":"
                + suffix;
    }

    private static String checkId(PrCheckRunState check)
    {
        return "failed-check-id:" + check.githubId();
    }

    private static String checkName(PrCheckRunState check)
    {
        return "failed-check-name:" + normalize(check.name());
    }

    private static boolean failedCheck(PrCheckRunState check)
    {
        return "COMPLETED".equals(normalize(check.status()))
                && ImmutableSet.of("FAILURE", "CANCELLED", "TIMED_OUT",
                        "ACTION_REQUIRED", "STARTUP_FAILURE")
                .contains(normalize(check.conclusion()));
    }

    private static boolean successfulConclusion(String conclusion)
    {
        return ImmutableSet.of("SUCCESS", "NEUTRAL", "SKIPPED")
                .contains(normalize(conclusion));
    }

    private static <T> List<T> nullToEmpty(List<T> values)
    {
        return values == null ? List.of() : values;
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

    private record Target(PullRequestRef ref, String pat, PrRawDetail detail) {}

    private record BranchTarget(PullRequestRef repository, String pat) {}

    private record ObservedComment(String identity, String body) {}

    private record TriggerState(
            TriggerDisposition disposition,
            String localHead,
            String remoteHead,
            String prHead,
            String generatedHead) {}

    private enum TriggerDisposition
    {
        READY_CREATE,
        READY_PUSH,
        WAITING_FOR_PR_HEAD,
        PROVEN
    }

    private static final class CiTriggerMutationFailure
            extends RuntimeException
    {
        private CiTriggerMutationFailure(Exception cause)
        {
            super(cause);
        }
    }

    @FunctionalInterface
    private interface Mutation
    {
        void apply(Target target);
    }

    private enum TargetPolicy
    {
        OPEN,
        MERGED,
        OPEN_OR_TERMINAL,
        OPEN_OR_CLOSED;

        private static TargetPolicy forAction(
                UserRemoteActionOperationHandler.SemanticAction action)
        {
            return switch (action) {
                case DELETE_REMOTE_BRANCH -> MERGED;
                case POST_TOP_LEVEL_COMMENT -> OPEN_OR_TERMINAL;
                case MERGE -> OPEN_OR_TERMINAL;
                case CLOSE_PULL_REQUEST, COMMENT_AND_CLOSE -> OPEN_OR_CLOSED;
                case DEQUEUE, SUBMIT_REVIEW, RERUN_FAILED_CHECKS,
                        SET_DRAFT_STATE, UPDATE_TITLE, UPDATE_BODY,
                        REPLY_REVIEW_THREAD, EDIT_ISSUE_COMMENT,
                        EDIT_REVIEW_COMMENT, DELETE_ISSUE_COMMENT,
                        DELETE_REVIEW_COMMENT, ADD_REVIEWER, REMOVE_REVIEWER,
                        SET_ASSIGNEE, SET_LABEL, CREATE_INLINE_COMMENT,
                        REACT_PULL_REQUEST, REACT_REVIEW_COMMENT,
                        REACT_ISSUE_COMMENT, SET_THREAD_RESOLUTION,
                        ENABLE_AUTO_MERGE, DISABLE_AUTO_MERGE,
                        APPLY_SUGGESTION, TRIGGER_CI_EMPTY_COMMIT -> OPEN;
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
                case OPEN_OR_CLOSED -> open
                        || !detail.merged()
                        && "CLOSED".equals(normalize(detail.state()));
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
