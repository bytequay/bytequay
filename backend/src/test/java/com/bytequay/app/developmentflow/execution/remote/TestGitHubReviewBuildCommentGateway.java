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
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.RetryableActionException;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubReviewBuildCommentGateway
{
    private static final Instant AUTHORIZED =
            Instant.parse("2026-07-29T00:00:00Z");
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of(
            "acme", "widget", 17);

    private PullRequestRepository pullRequests;
    private ExecutionContext execution;
    private GitHubReviewBuildCommentGateway gateway;

    @BeforeEach
    void setUp()
    {
        pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("base-pat");
        gateway = new GitHubReviewBuildCommentGateway(pullRequests, pats);
    }

    @Test
    void movedHeadRejectsTheFrozenAuthorizationBeforeAnyReviewCall()
    {
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-2"));

        assertThatThrownBy(() -> gateway.captureBaseline(action(), execution))
                .isInstanceOf(RetryableActionException.class)
                .hasMessageContaining("moved outside");
        verify(pullRequests, never()).listReviews("base-pat", PULL_REQUEST);
    }

    @Test
    void movedHeadImmediatelyBeforeCreationRejectsTheMutation()
    {
        when(pullRequests.fetchUserProfile("base-pat"))
                .thenReturn(user("alice"));
        when(pullRequests.listReviews("base-pat", PULL_REQUEST))
                .thenReturn(List.of());
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-2"));

        assertThatThrownBy(() -> gateway.execute(action(), execution))
                .isInstanceOf(RetryableActionException.class)
                .hasMessageContaining("moved outside");
        verify(pullRequests, never()).createReview(any(), any(), any());
    }

    @Test
    void ambiguousRecoveryNeverSubmitsAnotherReview()
    {
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-1"));
        when(pullRequests.fetchUserProfile("base-pat"))
                .thenReturn(user("alice"));
        when(pullRequests.listReviews("base-pat", PULL_REQUEST))
                .thenReturn(List.of(review(91), review(92)));

        assertThatThrownBy(() -> gateway.execute(action(), execution))
                .isInstanceOf(
                        ExecutionPorts.IndeterminateExecutionException.class)
                .hasMessageContaining("multiple matching");
        verify(pullRequests, never()).createReview(
                any(), any(), any());
    }

    @Test
    void exactPostBaselineReviewAndInlineMultisetAreProven()
            throws Exception
    {
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-1"));
        when(pullRequests.fetchUserProfile("base-pat"))
                .thenReturn(user("alice"));
        when(pullRequests.listReviews("base-pat", PULL_REQUEST))
                .thenReturn(List.of(review(80), review(91)));
        when(pullRequests.fetchAllPrReviewComments(
                "base-pat", PULL_REQUEST))
                .thenReturn(PullRequestRepository.Paged.complete(
                        List.of(comment(91))));

        var result = gateway.probe(action(), execution);

        assertThat(result.proven()).isTrue();
        assertThat(result.externalEffectId()).isEqualTo("review:91");
    }

    @Test
    void baselineIdentityProvesAReviewDespiteRemoteClockSkew()
            throws Exception
    {
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-1"));
        when(pullRequests.fetchUserProfile("base-pat"))
                .thenReturn(user("alice"));
        when(pullRequests.listReviews("base-pat", PULL_REQUEST))
                .thenReturn(List.of(review(
                        91, AUTHORIZED.minusSeconds(90))));
        when(pullRequests.fetchAllPrReviewComments(
                "base-pat", PULL_REQUEST))
                .thenReturn(PullRequestRepository.Paged.complete(
                        List.of(comment(91))));

        var result = gateway.probe(action(), execution);

        assertThat(result.proven()).isTrue();
        assertThat(result.externalEffectId()).isEqualTo("review:91");
    }

    @Test
    void visibleReviewWaitsForItsTemporarilyMissingInlineComments()
            throws Exception
    {
        when(pullRequests.fetchUserProfile("base-pat"))
                .thenReturn(user("alice"));
        when(pullRequests.listReviews("base-pat", PULL_REQUEST))
                .thenReturn(List.of(review(91)));
        when(pullRequests.fetchAllPrReviewComments(
                "base-pat", PULL_REQUEST))
                .thenReturn(PullRequestRepository.Paged.complete(List.of()));

        var result = gateway.probe(action(), execution);

        assertThat(result.proven()).isFalse();
        assertThat(result.evidence()).contains("waiting", "inline comments");
    }

    @Test
    void recoveryAdoptsTheFrozenHeadReviewAfterThePrHeadAdvances()
            throws Exception
    {
        when(pullRequests.fetchPrDetail("base-pat", PULL_REQUEST))
                .thenReturn(detail("head-2"));
        when(pullRequests.fetchUserProfile("base-pat"))
                .thenReturn(user("alice"));
        when(pullRequests.listReviews("base-pat", PULL_REQUEST))
                .thenReturn(List.of(review(91)));
        when(pullRequests.fetchAllPrReviewComments(
                "base-pat", PULL_REQUEST))
                .thenReturn(PullRequestRepository.Paged.complete(
                        List.of(comment(91))));

        var result = gateway.probe(action(), execution);

        assertThat(result.proven()).isTrue();
        assertThat(result.externalEffectId()).isEqualTo("review:91");
        verify(pullRequests, never()).fetchPrDetail(
                "base-pat", PULL_REQUEST);
    }

    private static CommentAction action()
    {
        FrozenDraft draft = new FrozenDraft(
                "proposal:finding-1", "file-line", "src/A.java", 12,
                "RIGHT", null, null, "Fix it", "finding-1");
        return new CommentAction(
                "action-1", "operation-1", ActionStatus.CLAIMED,
                1, 1, 3, "trunk-1", "pass-1", "command-1", "workspace",
                "acme/widget", "fork/widget", 17, "feature", "head-1",
                "{}", "digest",
                new ActionPayload(1, "Summary", "COMMENT", null,
                        List.of(draft)),
                AUTHORIZED, List.of("review:80"), null, null);
    }

    private static PrRawDetail detail(String head)
    {
        return new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), head, "feature", "fork/widget", "main",
                "acme/widget", "open", false, "base-1", null);
    }

    private static PullRequestReview review(long id)
    {
        return review(id, AUTHORIZED.plusSeconds(1));
    }

    private static PullRequestReview review(long id, Instant submittedAt)
    {
        return new PullRequestReview(
                id, "alice", "Summary", "COMMENTED", "head-1",
                submittedAt, "url");
    }

    private static PrReviewThreadMessage comment(long reviewId)
    {
        return new PrReviewThreadMessage(
                101, null, reviewId, "alice", "Fix it", "src/A.java", 12,
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
