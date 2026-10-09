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
package com.bytequay.app.service.pr;

import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRateLimitException;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@ExtendWith(MockitoExtension.class)
class TestPullRequestDetailFetcher
{
    private static final PullRequestRef REF = PullRequestRef.of("owner", "repo", 7);

    @Mock private GitHubPullRequestReadClient gitHub;
    @Mock private GitHubActionsClient actions;

    private PullRequestDetailFetcher fetcher;

    @BeforeEach
    void setUp()
    {
        fetcher = new PullRequestDetailFetcher(gitHub, actions, Runnable::run);
        lenient().when(gitHub.fetchPrCommits("pat", REF)).thenReturn(ImmutableList.of());
        lenient().when(gitHub.fetchPrReviewThreads("pat", REF)).thenReturn(ImmutableList.of());
        lenient().when(gitHub.enrichTimelineReferences(eq("pat"), eq(REF), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    void assemblesDetailFromAllFetches()
    {
        PrReviewState review = new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null);
        PrCheckRunState check = new PrCheckRunState(1L, "build", "completed", "success", null, "check_run", null, null);
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw("head"));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(ImmutableList.of(review));
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of());
        when(actions.fetchPrCheckRuns("pat", "owner", "repo", "head")).thenReturn(ImmutableList.of(check));

        StoredPrDetail stored = PullRequestDetailFetcher.await(fetcher.fetch("pat", REF));

        assertThat(stored.raw().body()).isEqualTo("body");
        assertThat(stored.reviews()).containsExactly(review);
        assertThat(stored.timeline()).isEmpty();
        assertThat(stored.checkRuns()).containsExactly(check);
    }

    @Test
    void treatsNullListsAsEmpty()
    {
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw("head"));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(null);
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(null);
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(null);
        when(actions.fetchPrCheckRuns("pat", "owner", "repo", "head")).thenReturn(null);

        StoredPrDetail stored = PullRequestDetailFetcher.await(fetcher.fetch("pat", REF));

        assertThat(stored.reviews()).isEmpty();
        assertThat(stored.timeline()).isEmpty();
        assertThat(stored.checkRuns()).isEmpty();
    }

    @Test
    void skipsCheckRunsWithoutHeadSha()
    {
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw(null));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of());

        StoredPrDetail stored = PullRequestDetailFetcher.await(fetcher.fetch("pat", REF));

        assertThat(stored.checkRuns()).isEmpty();
        verifyNoInteractions(actions);
    }

    @Test
    void rejectsEmptyDetailAsBadGateway()
    {
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(null);
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of());

        assertThatThrownBy(() -> PullRequestDetailFetcher.await(fetcher.fetch("pat", REF)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(BAD_GATEWAY));
        verifyNoInteractions(actions);
    }

    @Test
    void reportsAnOptionalSourceFailureWithoutHidingThePullRequest()
    {
        ResponseStatusException notFound = new ResponseStatusException(NOT_FOUND, "gone");
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw(null));
        when(gitHub.fetchPrReviews("pat", REF)).thenThrow(notFound);
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of());

        StoredPrDetail stored = PullRequestDetailFetcher.await(fetcher.fetch("pat", REF));

        assertThat(stored.reviews()).isEmpty();
        assertThat(stored.unavailableSections()).containsExactly("reviews");
    }

    @Test
    void aRateLimitOnSeveralSourcesKeepsItsStatus()
    {
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw(null));
        when(gitHub.fetchPrReviews("pat", REF)).thenThrow(new GitHubRateLimitException("secondary", 60));
        when(gitHub.fetchPrTimeline("pat", REF)).thenThrow(new GitHubRateLimitException("secondary", 60));

        assertThatThrownBy(() -> PullRequestDetailFetcher.await(fetcher.fetch("pat", REF))).isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    void prefersIssueCommentCopyOverTimelineDuplicate()
    {
        Instant commentedAt = Instant.parse("2026-08-15T10:00:00Z");
        PrTimelineEvent timelineCopy = comment(1L, "alice", commentedAt, "plain copy");
        PrTimelineEvent labeled = new PrTimelineEvent(2L, "labeled", "bob", commentedAt, null, Optional.empty());
        PrTimelineEvent issueCopy = comment(3L, "alice", commentedAt, "copy with reactions");
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw(null));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of(timelineCopy, labeled));
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of(issueCopy));

        StoredPrDetail stored = PullRequestDetailFetcher.await(fetcher.fetch("pat", REF));

        assertThat(stored.timeline()).containsExactly(labeled, issueCopy);
    }

    @Test
    void refetchesTheFullMutableTimeline()
    {
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw(null));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of());

        PullRequestDetailFetcher.await(fetcher.fetch("pat", REF));

        verify(gitHub).fetchPrTimeline("pat", REF);
        verify(gitHub).fetchPrIssueComments("pat", REF);
    }

    @Test
    void discussionRefreshReusesMetadataAndChecksWithoutFetchingThemAgain()
    {
        PrRawDetail raw = raw("head");
        StoredPrDetail existing = new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());

        StoredPrDetail refreshed = PullRequestDetailFetcher.await(fetcher.fetchDiscussion("pat", REF, existing));

        assertThat(refreshed.raw()).isSameAs(raw);
        verify(gitHub).fetchPrIssueComments("pat", REF);
        verify(gitHub).fetchPrReviews("pat", REF);
        verify(gitHub, org.mockito.Mockito.never()).fetchPrDetail("pat", REF);
        verify(gitHub, org.mockito.Mockito.never()).fetchPrCommits("pat", REF);
        org.mockito.Mockito.verifyNoInteractions(actions);
    }

    private static PrRawDetail raw(String headSha)
    {
        return new PrRawDetail(
                null, null, null, null, null, null, null, null, "body", ImmutableList.of(), null, ImmutableList.of(), null, false, true, "clean", 4, 2, 1, 0, ImmutableList.of(),
                headSha, "feature", null);
    }

    private static PrTimelineEvent comment(Long githubId, String actor, Instant timestamp, String body)
    {
        return new PrTimelineEvent(githubId, "commented", actor, timestamp, body, Optional.empty());
    }
}
