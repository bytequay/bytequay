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
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.RetryableActionException;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private GitHubUserRemoteActionGateway gateway;

    @BeforeEach
    void setUp()
    {
        pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("base-pat");
        when(pats.resolve("fork/widget")).thenReturn("head-pat");
        gateway = new GitHubUserRemoteActionGateway(pullRequests, pats);
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
