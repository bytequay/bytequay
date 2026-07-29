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
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.RetryableActionException;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubUserRemoteActionGateway
{
    private static final Instant AUTHORIZED = Instant.parse("2026-07-29T00:00:00Z");
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of(
            "acme", "widget", 17);

    private PullRequestRepository pullRequests;
    private ExecutionContext execution;
    private GitRunner git;
    private WorktreeWriterLeaseManager writers;
    private GitHubUserRemoteActionGateway gateway;

    @BeforeEach
    void setUp()
    {
        pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        execution = mock(ExecutionContext.class);
        git = mock(GitRunner.class);
        writers = mock(WorktreeWriterLeaseManager.class);
        when(pats.resolve("acme/widget")).thenReturn("base-pat");
        when(pats.resolve("fork/widget")).thenReturn("head-pat");
        gateway = new GitHubUserRemoteActionGateway(
                pullRequests, pats, git, writers);
    }

    @Test
    void probeBeforeExecuteRecoversOneExactCommentWithoutPostingAgain()
            throws Exception
    {
        exactOpenSubject("head-1");
        when(pullRequests.fetchUserProfile("base-pat")).thenReturn(user("alice"));
        when(pullRequests.fetchPrIssueComments(
                "base-pat", PULL_REQUEST, AUTHORIZED)).thenReturn(List.of(
                        comment(91, "alice", "looks good", AUTHORIZED.plusSeconds(1))));

        var result = gateway.execute(
                action(ActionKind.POST_TOP_LEVEL_COMMENT,
                        new ActionPayload(
                                1, "looks good", null, null, List.of())),
                execution);

        assertThat(result.proven()).isTrue();
        assertThat(result.externalEffectId()).isEqualTo("issue-comment:91");
        verify(pullRequests, never()).createIssueComment(any(), any(), any());
    }

    @Test
    void newCommandDoesNotAdoptAnOlderSameSecondComment()
            throws Exception
    {
        exactOpenSubject("head-1");
        PrTimelineEvent old = comment(
                91, "alice", "same", AUTHORIZED);
        when(pullRequests.fetchPrIssueComments(
                "base-pat", PULL_REQUEST, AUTHORIZED))
                .thenReturn(List.of(old), List.of(old));
        when(pullRequests.fetchUserProfile("base-pat")).thenReturn(user("alice"));
        when(pullRequests.createIssueComment(
                "base-pat", PULL_REQUEST, "same")).thenReturn(
                        comment(92, "alice", "same", AUTHORIZED));
        Action candidate = action(
                ActionKind.POST_TOP_LEVEL_COMMENT,
                new ActionPayload(1, "same", null, null, List.of()),
                AUTHORIZED, "command-2", null);

        List<String> baseline = gateway.captureBaseline(candidate, execution);
        var result = gateway.execute(
                action(ActionKind.POST_TOP_LEVEL_COMMENT, candidate.payload(),
                        AUTHORIZED, "command-2", baseline),
                execution);

        assertThat(baseline).containsExactly("issue-comment:91");
        assertThat(result.externalEffectId()).isEqualTo("issue-comment:92");
        verify(pullRequests).createIssueComment(
                "base-pat", PULL_REQUEST, "same");
    }

    @Test
    void exactMergedTargetStillAllowsATopLevelComment()
            throws Exception
    {
        exactMergedSubject();
        when(pullRequests.fetchUserProfile("base-pat")).thenReturn(user("alice"));
        when(pullRequests.fetchPrIssueComments(
                "base-pat", PULL_REQUEST, AUTHORIZED)).thenReturn(List.of());
        when(pullRequests.createIssueComment(
                "base-pat", PULL_REQUEST, "after merge")).thenReturn(
                        comment(93, "alice", "after merge",
                                AUTHORIZED.plusSeconds(1)));

        var result = gateway.execute(
                action(ActionKind.POST_TOP_LEVEL_COMMENT,
                        new ActionPayload(
                                1, "after merge", null, null, List.of())),
                execution);

        assertThat(result.externalEffectId()).isEqualTo("issue-comment:93");
    }

    @Test
    void mergeFreezesMethodAndExactHeadThenProvesMerged()
            throws Exception
    {
        exactOpenSubject("head-1");
        when(pullRequests.isPullRequestMerged("base-pat", PULL_REQUEST))
                .thenReturn(false, false, true);
        when(pullRequests.fetchMergeQueueInfo("base-pat", PULL_REQUEST))
                .thenReturn(new PullRequestRepository.MergeQueueInfo(false, null));
        when(pullRequests.probeMergeQueue("base-pat", PULL_REQUEST))
                .thenReturn(Optional.empty());
        when(pullRequests.mergePullRequest(
                any(), any(), any())).thenReturn(
                        new MergeResult("merge-sha", true, "merged"));
        Action action = action(
                SemanticAction.MERGE, ActionPayload.value("SQUASH"),
                AUTHORIZED, "merge-command", List.of());

        var result = gateway.execute(action, execution);

        assertThat(result.proven()).isTrue();
        ArgumentCaptor<MergePullRequestCommand> command =
                ArgumentCaptor.forClass(MergePullRequestCommand.class);
        verify(pullRequests).mergePullRequest(
                eq("base-pat"),
                eq(PULL_REQUEST),
                command.capture());
        assertThat(command.getValue().mergeMethod()).isEqualTo("squash");
        assertThat(command.getValue().sha()).contains("head-1");
    }

    @Test
    void autoMergeEnableAndDisableRequireExactProbes()
            throws Exception
    {
        exactOpenSubject("head-1");
        when(pullRequests.fetchAutoMergeStatus("base-pat", PULL_REQUEST))
                .thenReturn(Optional.empty(),
                        Optional.of(new PullRequestRepository.AutoMergeStatus(
                                "REBASE", "alice")),
                        Optional.of(new PullRequestRepository.AutoMergeStatus(
                                "REBASE", "alice")), Optional.empty());

        var enabled = gateway.execute(action(
                SemanticAction.ENABLE_AUTO_MERGE,
                ActionPayload.value("REBASE"), AUTHORIZED,
                "enable-command", List.of()), execution);
        var disabled = gateway.execute(action(
                SemanticAction.DISABLE_AUTO_MERGE,
                ActionPayload.empty(), AUTHORIZED, "disable-command",
                List.of()), execution);

        assertThat(enabled.proven()).isTrue();
        assertThat(disabled.proven()).isTrue();
        verify(pullRequests).enableAutoMerge(
                "base-pat", PULL_REQUEST, "REBASE");
        verify(pullRequests).disableAutoMerge("base-pat", PULL_REQUEST);
    }

    @Test
    void ambiguousCommentProbeNeverPostsAnotherCopy()
    {
        exactOpenSubject("head-1");
        when(pullRequests.fetchUserProfile("base-pat")).thenReturn(user("alice"));
        when(pullRequests.fetchPrIssueComments(
                "base-pat", PULL_REQUEST, AUTHORIZED)).thenReturn(List.of(
                        comment(91, "alice", "same", AUTHORIZED.plusSeconds(1)),
                        comment(92, "alice", "same", AUTHORIZED.plusSeconds(2))));

        assertThatThrownBy(() -> gateway.execute(
                action(ActionKind.POST_TOP_LEVEL_COMMENT,
                        new ActionPayload(1, "same", null, null, List.of())),
                execution))
                .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class)
                .hasMessageContaining("multiple matching effects");
        verify(pullRequests, never()).createIssueComment(any(), any(), any());
    }

    @Test
    void recoveryIncludesAnEffectTimestampedAtGitHubsSecondPrecision()
            throws Exception
    {
        Instant preciseAuthorization = AUTHORIZED.plusMillis(750);
        exactOpenSubject("head-1");
        when(pullRequests.fetchUserProfile("base-pat")).thenReturn(user("alice"));
        when(pullRequests.fetchPrIssueComments(
                "base-pat", PULL_REQUEST, AUTHORIZED)).thenReturn(List.of(
                        comment(93, "alice", "same second", AUTHORIZED)));

        var result = gateway.execute(
                action(
                        ActionKind.POST_TOP_LEVEL_COMMENT,
                        new ActionPayload(
                                1, "same second", null, null, List.of()),
                        preciseAuthorization),
                execution);

        assertThat(result.externalEffectId()).isEqualTo("issue-comment:93");
        verify(pullRequests, never()).createIssueComment(any(), any(), any());
    }

    @Test
    void dequeueExecutesThenRequiresAnIndependentAbsentProbe()
            throws Exception
    {
        exactOpenSubject("head-1");
        when(pullRequests.fetchMergeQueueInfo("base-pat", PULL_REQUEST))
                .thenReturn(
                        new PullRequestRepository.MergeQueueInfo(true, "QUEUED"),
                        new PullRequestRepository.MergeQueueInfo(true, null));

        var result = gateway.execute(
                action(ActionKind.DEQUEUE,
                        new ActionPayload(1, null, null, null, List.of())),
                execution);

        assertThat(result.externalEffectId())
                .isEqualTo("merge-queue:absent:acme/widget#17");
        verify(pullRequests).dequeuePullRequest("base-pat", PULL_REQUEST);
    }

    @Test
    void branchMoveRejectsDeletionBeforeTheRemoteMutation()
    {
        exactMergedSubject();
        PullRequestRef headRepository = PullRequestRef.of("fork", "widget", 17);
        when(pullRequests.fetchBranchHeadSha(
                "head-pat", headRepository, "feature"))
                .thenReturn(Optional.of("head-moved"));

        assertThatThrownBy(() -> gateway.execute(
                action(ActionKind.DELETE_REMOTE_BRANCH,
                        new ActionPayload(
                                1, null, null, "feature", List.of())),
                execution))
                .isInstanceOf(RetryableActionException.class)
                .hasMessageContaining("branch moved");
        verify(pullRequests, never()).deleteBranch(any(), any(), any());
    }

    @Test
    void exactReviewAndLineSetAreRecoveredWithoutResubmission()
            throws Exception
    {
        exactOpenSubject("head-1");
        when(pullRequests.fetchUserProfile("base-pat")).thenReturn(user("alice"));
        when(pullRequests.listReviews("base-pat", PULL_REQUEST)).thenReturn(List.of(
                new PullRequestReview(
                        71, "alice", "summary", "CHANGES_REQUESTED", "head-1",
                        AUTHORIZED.plusSeconds(1), "review-url")));
        when(pullRequests.fetchPrReviewComments(
                "base-pat", PULL_REQUEST, AUTHORIZED)).thenReturn(List.of(
                        reviewComment(81, 71, "change this")));
        FrozenDraft draft = new FrozenDraft(
                "draft-1", "file-line", "src/A.java", 12, "RIGHT",
                null, null, "change this", null);

        var result = gateway.execute(
                action(ActionKind.SUBMIT_REVIEW,
                        new ActionPayload(
                                1, "summary", "REQUEST_CHANGES", null,
                                List.of(draft))),
                execution);

        assertThat(result.externalEffectId()).isEqualTo("review:71");
        verify(pullRequests, never()).createReview(any(), any(), any());
    }

    @Test
    void commentAndCloseResumesAfterItsPostBaselineCommentThenCloses()
            throws Exception
    {
        AtomicBoolean closed = new AtomicBoolean();
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenAnswer(ignored -> detail(
                        "head-1", closed.get() ? "closed" : "open", false));
        when(pullRequests.fetchUserProfile("base-pat")).thenReturn(user("alice"));
        PrTimelineEvent created = comment(
                94, "alice", "closing now", AUTHORIZED.plusSeconds(1));
        when(pullRequests.fetchPrIssueComments(
                "base-pat", PULL_REQUEST, AUTHORIZED))
                .thenReturn(List.of(created));
        when(pullRequests.updatePullRequest(any(), any(), any()))
                .thenAnswer(ignored -> {
                    closed.set(true);
                    return null;
                });
        Action action = action(
                SemanticAction.COMMENT_AND_CLOSE,
                ActionPayload.body("closing now"), AUTHORIZED,
                "comment-close-command", List.of());

        var result = gateway.execute(action, execution);

        assertThat(result.proven()).isTrue();
        assertThat(result.externalEffectId())
                .isEqualTo("comment-and-close:issue-comment:94");
        verify(pullRequests, never()).createIssueComment(any(), any(), any());
        verify(pullRequests).updatePullRequest(any(), any(), any());
    }

    @Test
    void closeNeverAdmitsAnExactMergedPullRequest()
    {
        exactMergedSubject();

        assertThatThrownBy(() -> gateway.probe(
                action(SemanticAction.CLOSE_PULL_REQUEST,
                        ActionPayload.empty(), AUTHORIZED,
                        "close-command", List.of()),
                execution))
                .isInstanceOf(RetryableActionException.class)
                .hasMessageContaining("outside the exact user authorization");
        verify(pullRequests, never()).updatePullRequest(any(), any(), any());
    }

    @Test
    void emptyCommitTriggerCreatesAndPushesUnderTheWriterFence()
            throws Exception
    {
        Path worktree = Path.of("/tmp/task-ci-trigger");
        Action action = ciTriggerAction(worktree);
        writerFence(action, worktree);
        exactCiTriggerGit(worktree);
        when(git.headSha(worktree)).thenReturn("head-1", "head-1", "head-2");
        when(git.remoteHeadSha(worktree, "origin", "feature"))
                .thenReturn(Optional.of("head-1"), Optional.of("head-1"),
                        Optional.of("head-2"));
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-1", "open", false),
                        detail("head-1", "open", false),
                        detail("head-2", "open", false));
        when(git.commitEmpty(worktree,
                "Re-trigger CI [bytequay:operation-1]")).thenReturn("head-2");

        var result = gateway.execute(action, execution);

        assertThat(result.proven()).isTrue();
        assertThat(result.externalEffectId())
                .isEqualTo("ci-trigger-empty-commit:head-2");
        verify(git).commitEmpty(
                worktree, "Re-trigger CI [bytequay:operation-1]");
        verify(git).push(worktree);
        verify(writers).acquire(execution, worktree.toString());
    }

    @Test
    void emptyCommitTriggerResumesTheExactCommitWithoutCreatingAnother()
            throws Exception
    {
        Path worktree = Path.of("/tmp/task-ci-trigger-recovery");
        Action action = ciTriggerAction(worktree);
        writerFence(action, worktree);
        exactCiTriggerGit(worktree);
        when(git.headSha(worktree)).thenReturn("head-2");
        when(git.remoteHeadSha(worktree, "origin", "feature"))
                .thenReturn(Optional.of("head-1"), Optional.of("head-1"),
                        Optional.of("head-2"));
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-1", "open", false),
                        detail("head-1", "open", false),
                        detail("head-2", "open", false));

        var result = gateway.execute(action, execution);

        assertThat(result.proven()).isTrue();
        assertThat(result.externalEffectId())
                .isEqualTo("ci-trigger-empty-commit:head-2");
        verify(git, never()).commitEmpty(any(), any());
        verify(git).push(worktree);
    }

    @Test
    void emptyCommitTriggerWaitsForPrHeadWithoutPushingTwice()
            throws Exception
    {
        Path worktree = Path.of("/tmp/task-ci-trigger-pushed");
        Action action = ciTriggerAction(worktree);
        exactCiTriggerGit(worktree);
        when(git.headSha(worktree)).thenReturn("head-2");
        when(git.remoteHeadSha(worktree, "origin", "feature"))
                .thenReturn(Optional.of("head-2"));
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-1", "open", false),
                        detail("head-2", "open", false));

        var waiting = gateway.execute(action, execution);
        var recovered = gateway.execute(action, execution);

        assertThat(waiting.proven()).isFalse();
        assertThat(recovered.proven()).isTrue();
        assertThat(recovered.externalEffectId())
                .isEqualTo("ci-trigger-empty-commit:head-2");
        verify(git, never()).commitEmpty(any(), any());
        verify(git, never()).push(any());
    }

    @SuppressWarnings("unchecked")
    private void writerFence(Action action, Path worktree)
    {
        WorktreeWriterLeaseManager.Lease lease = mock(
                WorktreeWriterLeaseManager.Lease.class);
        WorktreeWriterLeaseManager.WriterAuthorization authorization = mock(
                WorktreeWriterLeaseManager.WriterAuthorization.class);
        WorktreeWriterLeaseManager.MutationFence fence = mock(
                WorktreeWriterLeaseManager.MutationFence.class);
        when(writers.acquire(execution, worktree.toString())).thenReturn(lease);
        when(writers.authorizeMutation(execution, lease))
                .thenReturn(authorization);
        when(fence.worktreePath()).thenReturn(worktree.toString());
        when(fence.taskId()).thenReturn(action.taskId());
        when(fence.operationId()).thenReturn(action.operationId());
        when(fence.taskEpoch()).thenReturn(action.taskEpoch());
        when(authorization.run(any())).thenAnswer(invocation -> {
            Function<WorktreeWriterLeaseManager.MutationFence, Object> mutation =
                    invocation.getArgument(0);
            return mutation.apply(fence);
        });
    }

    private void exactCiTriggerGit(Path worktree)
            throws Exception
    {
        when(git.currentBranch(worktree)).thenReturn("feature");
        when(git.remoteSlug(worktree, "origin"))
                .thenReturn(Optional.of(RepoRef.parse("fork/widget")));
        when(git.resolveCommitSha(worktree, "head-2"))
                .thenReturn(Optional.of("head-2"));
        when(git.resolveCommitSha(worktree, "head-2^"))
                .thenReturn(Optional.of("head-1"));
        when(git.listCommits(worktree, "head-2", 1)).thenReturn(List.of(
                new GitRunner.CommitEntry(
                        "head-2", "head-2", "ByteQuay", "app@example.test",
                        AUTHORIZED.toString(),
                        "Re-trigger CI [bytequay:operation-1]")));
        when(git.diff(worktree, "head-1", "head-2", 1024)).thenReturn("");
    }

    private static Action ciTriggerAction(Path worktree)
    {
        return new Action(
                "action-1", "operation-1", ActionKind.DEQUEUE,
                SemanticAction.TRIGGER_CI_EMPTY_COMMIT,
                ActionStatus.REQUESTED, 1, 1, 3, "task-1", "command-1",
                1, "stage-1", 1, "binding-1", "pr-1", "acme/widget",
                "fork/widget", 17, "feature", worktree.toString(),
                "fingerprint-1", "head-1", "base-1", "{}", "digest",
                ActionPayload.empty(), null, AUTHORIZED, List.of(), null, null);
    }

    private void exactOpenSubject(String head)
    {
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST)).thenReturn(
                detail(head, "open", false));
    }

    private void exactMergedSubject()
    {
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST)).thenReturn(
                detail("head-1", "closed", true));
    }

    private static PrRawDetail detail(
            String head, String state, boolean merged)
    {
        return new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), head, "feature", "fork/widget", "main",
                "acme/widget", state, merged, "base-1", null);
    }

    private static Action action(ActionKind kind, ActionPayload payload)
    {
        return action(kind, payload, AUTHORIZED);
    }

    private static Action action(
            ActionKind kind, ActionPayload payload, Instant authorizedAt)
    {
        return action(
                kind, payload, authorizedAt, "command-1", List.of());
    }

    private static Action action(
            ActionKind kind,
            ActionPayload payload,
            Instant authorizedAt,
            String commandId,
            List<String> baseline)
    {
        return new Action(
                "action-1", "operation-1", kind, ActionStatus.REQUESTED,
                1, 0, 3, "task-1", commandId, 1, "stage-1", 1,
                "binding-1", "pr-1",
                "acme/widget", "fork/widget", 17, "feature", "head-1",
                "base-1", "{}", "digest", payload, "COMMENTED", authorizedAt,
                baseline, null, null);
    }

    private static Action action(
            SemanticAction semanticAction,
            ActionPayload payload,
            Instant authorizedAt,
            String commandId,
            List<String> baseline)
    {
        return new Action(
                "action-1", "operation-1", semanticAction.wireKind(),
                semanticAction, ActionStatus.REQUESTED,
                1, 1, 3, "task-1", commandId, 1, "stage-1", 1,
                "binding-1", "pr-1", "acme/widget", "fork/widget", 17,
                "feature", "head-1", "base-1", "{}", "digest", payload,
                null, authorizedAt, baseline, null, null);
    }

    private static PrTimelineEvent comment(
            long id, String author, String body, Instant createdAt)
    {
        return new PrTimelineEvent(
                id, "commented", author, null, createdAt, body,
                null, null, null, null, null, null);
    }

    private static PrReviewThreadMessage reviewComment(
            long id, long reviewId, String body)
    {
        return new PrReviewThreadMessage(
                id, null, reviewId, "alice", body, "src/A.java", 12,
                "RIGHT", "@@", "head-1", AUTHORIZED.plusSeconds(1), null,
                false, null, null, 12, null, "MEMBER", "thread-1", false,
                null);
    }

    private static UserProfile user(String login)
    {
        return new UserProfile(
                login, login, "avatar", "html", 0, 0, 0,
                null, null, null, null, false);
    }
}
