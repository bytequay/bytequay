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
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestRepository.MergeQueueInfo;
import com.bytequay.app.repository.PullRequestRepository.ReviewThreadMeta;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.CollaboratorPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubRemoteObserver
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final RepoRef REPOSITORY = RepoRef.parse("acme/widget");
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of(
            "acme", "widget", 17);

    @Test
    void capturesOneExactFailClosedSnapshot()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(pullRequests.fetchUserProfile("pat")).thenReturn(user("me"));
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST)).thenReturn(
                new PrRawDetail(
                        "body", List.of(), false, true, "clean", 1, 1, 1,
                        2, List.of("carol", "dave"), "head-1", "feature",
                        "acme/widget", "main", "acme/widget", "open", false,
                        "base-1", null));
        when(pullRequests.fetchPrReviews("pat", PULL_REQUEST)).thenReturn(List.of(
                new PrReviewState("Alice", "APPROVED", NOW.minusSeconds(30)),
                new PrReviewState("alice", "CHANGES_REQUESTED", NOW.minusSeconds(10)),
                new PrReviewState("Bob", "APPROVED", NOW.minusSeconds(5))));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "head-1")).thenReturn(List.of(
                    new PrCheckRunState(11L, "build", "completed", "success",
                            null, null, null),
                    new PrCheckRunState(12L, "test", "in_progress", null,
                            null, null, null)));
        when(pullRequests.fetchReviewThreadResolution("pat", PULL_REQUEST))
                .thenReturn(List.of(
                        new ReviewThreadMeta(41L, "thread-41", false, null),
                        new ReviewThreadMeta(42L, "thread-42", true, "alice")));
        when(pullRequests.fetchPrReviewComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrTimeline(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrIssueComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchMergeQueueInfo("pat", PULL_REQUEST))
                .thenReturn(new MergeQueueInfo(true, "QUEUED"));
        when(collaborators.countWriteApprovals(eq("pat"), eq(REPOSITORY), any()))
                .thenReturn(1);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of("build")),
                execution);

        assertThat(observed.headSha()).isEqualTo("head-1");
        assertThat(observed.baseSha()).isEqualTo("base-1");
        assertThat(observed.prState())
                .isEqualTo(RemoteObservationOperationHandler.PrState.OPEN);
        assertThat(observed.mergeability())
                .isEqualTo(RemoteObservationOperationHandler.Mergeability.MERGEABLE);
        assertThat(observed.mergeQueueState())
                .isEqualTo(RemoteObservationOperationHandler.MergeQueueState.QUEUED);
        assertThat(observed.effectiveApprovalCount()).isOne();
        assertThat(observed.writeApprovalCount()).isOne();
        assertThat(observed.changesRequestedCount()).isOne();
        assertThat(observed.unresolvedThreadCount()).isOne();
        assertThat(observed.checks())
                .extracting(RemoteCiPolicy.Check::state)
                .containsExactly(
                        RemoteCiPolicy.CheckState.PASSED,
                        RemoteCiPolicy.CheckState.PENDING);
        assertThat(observed.observedAtMs()).isEqualTo(NOW.toEpochMilli());
        assertThat(observed.rawEvidence()).contains("acme/widget", "head-1");
    }

    @Test
    void normalizesAllAddressableFeedbackWithExactTargets()
    {
        PrReviewThreadMessage root = comment(
                41L, null, "alice", "please fix", "thread-41");
        PrReviewThreadMessage ownReply = comment(
                43L, 41L, "me", "fixed", "thread-41");
        PrTimelineEvent topLevel = event(
                51L, "commented", "carol", null, "one more thing", null, null);
        PrTimelineEvent review = event(
                61L, "reviewed", "bob", "changes_requested",
                "please update tests", null, 61L);
        PrTimelineEvent requested = event(
                62L, "review_requested", "carol", null, null, "dave", null);

        List<RemoteObservationOperationHandler.FeedbackFact> facts =
                GitHubRemoteObserver.feedbackFacts(
                        List.of(root, ownReply),
                        List.of(
                                new ReviewThreadMeta(41L, "thread-41", false, null),
                                new ReviewThreadMeta(42L, "thread-42", true, "alice")),
                        List.of(review, requested), List.of(topLevel), "me",
                        new ObjectMapper().findAndRegisterModules());

        assertThat(facts)
                .extracting(RemoteObservationOperationHandler.FeedbackFact::kind)
                .containsExactlyInAnyOrder(
                        RemoteObservationOperationHandler.FeedbackKind.INLINE_COMMENT,
                        RemoteObservationOperationHandler.FeedbackKind.INLINE_COMMENT,
                        RemoteObservationOperationHandler.FeedbackKind.THREAD_REOPENED,
                        RemoteObservationOperationHandler.FeedbackKind.THREAD_RESOLVED,
                        RemoteObservationOperationHandler.FeedbackKind.TOP_LEVEL_COMMENT,
                        RemoteObservationOperationHandler.FeedbackKind.REVIEW_BODY,
                        RemoteObservationOperationHandler.FeedbackKind.REVIEW_VERDICT,
                        RemoteObservationOperationHandler.FeedbackKind.REQUESTED_REVIEW);
        assertThat(facts)
                .filteredOn(fact -> fact.externalKey().equals("inline-comment:43"))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.ownAction()).isTrue();
                    assertThat(fact.commentId()).isEqualTo("41");
                    assertThat(fact.threadId()).isEqualTo("thread-41");
                });
        assertThat(facts)
                .filteredOn(fact -> fact.kind()
                        == RemoteObservationOperationHandler.FeedbackKind.REVIEW_VERDICT)
                .singleElement()
                .extracting(RemoteObservationOperationHandler.FeedbackFact::verdict)
                .isEqualTo(
                        RemoteObservationOperationHandler.FeedbackVerdict.CHANGES_REQUESTED);
    }

    @Test
    void cancellationStopsBeforeAnyRemoteRead()
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(execution.isCancellationRequested()).thenReturn(true);
        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, mock(CollaboratorPermissionService.class),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution))
                .isInstanceOf(ExecutionPorts.OperationCanceledException.class);
        verify(pullRequests, never()).fetchPrDetail(any(), any());
    }

    private static PrReviewThreadMessage comment(
            long id, Long inReplyTo, String author, String body, String threadId)
    {
        return new PrReviewThreadMessage(
                id, inReplyTo, null, author, body, "src/App.java", 10, "RIGHT",
                "@@", "head-1", NOW.minusSeconds(10), null, false, null, null,
                10, null, "MEMBER", threadId, false, null);
    }

    private static PrTimelineEvent event(
            Long id,
            String kind,
            String actor,
            String state,
            String body,
            String requestedReviewer,
            Long reviewId)
    {
        return new PrTimelineEvent(
                id, kind, actor, state, NOW.minusSeconds(5), body, null, null,
                requestedReviewer, reviewId, "MEMBER", null);
    }

    private static UserProfile user(String login)
    {
        return new UserProfile(
                login, login, "avatar", "html", 0, 0, 0,
                null, null, null, null, false);
    }
}
