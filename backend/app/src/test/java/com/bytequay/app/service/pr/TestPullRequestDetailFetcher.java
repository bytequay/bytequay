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
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
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
    @Mock private PrDetailStore detailStore;

    private PullRequestDetailFetcher fetcher;

    @BeforeEach
    void setUp()
    {
        fetcher = new PullRequestDetailFetcher(gitHub, actions, detailStore, Runnable::run);
        lenient().when(gitHub.fetchPrCommits("pat", REF)).thenReturn(List.of());
        lenient().when(gitHub.fetchPrReviewThreads("pat", REF)).thenReturn(List.of());
        lenient().when(gitHub.enrichTimelineReferences(eq("pat"), eq(REF), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    void assemblesDetailFromAllFetches()
    {
        PrReviewState review = new PrReviewState("alice", "APPROVED");
        PrCheckRunState check = new PrCheckRunState(1L, "build", "completed", "success");
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw("head"));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(List.of(review));
        when(gitHub.fetchPrTimeline("pat", REF, null)).thenReturn(List.of());
        when(gitHub.fetchPrIssueComments("pat", REF, null)).thenReturn(List.of());
        when(actions.fetchPrCheckRuns("pat", "owner", "repo", "head")).thenReturn(List.of(check));

        StoredPrDetail stored = fetcher.fetch("pat", REF);

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
        when(gitHub.fetchPrTimeline("pat", REF, null)).thenReturn(null);
        when(gitHub.fetchPrIssueComments("pat", REF, null)).thenReturn(null);
        when(actions.fetchPrCheckRuns("pat", "owner", "repo", "head")).thenReturn(null);

        StoredPrDetail stored = fetcher.fetch("pat", REF);

        assertThat(stored.reviews()).isEmpty();
        assertThat(stored.timeline()).isEmpty();
        assertThat(stored.checkRuns()).isEmpty();
    }

    @Test
    void skipsCheckRunsWithoutHeadSha()
    {
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw(null));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(List.of());
        when(gitHub.fetchPrTimeline("pat", REF, null)).thenReturn(List.of());
        when(gitHub.fetchPrIssueComments("pat", REF, null)).thenReturn(List.of());

        StoredPrDetail stored = fetcher.fetch("pat", REF);

        assertThat(stored.checkRuns()).isEmpty();
        verifyNoInteractions(actions);
    }

    @Test
    void rejectsEmptyDetailAsBadGateway()
    {
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(null);
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(List.of());
        when(gitHub.fetchPrTimeline("pat", REF, null)).thenReturn(List.of());
        when(gitHub.fetchPrIssueComments("pat", REF, null)).thenReturn(List.of());

        assertThatThrownBy(() -> fetcher.fetch("pat", REF))
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
        when(gitHub.fetchPrTimeline("pat", REF, null)).thenReturn(List.of());
        when(gitHub.fetchPrIssueComments("pat", REF, null)).thenReturn(List.of());

        StoredPrDetail stored = fetcher.fetch("pat", REF);

        assertThat(stored.reviews()).isEmpty();
        assertThat(stored.unavailableSections()).containsExactly("reviews");
    }

    @Test
    void prefersIssueCommentCopyOverTimelineDuplicate()
    {
        Instant commentedAt = Instant.parse("2026-08-15T10:00:00Z");
        PrTimelineEvent timelineCopy = comment(1L, "alice", commentedAt, "plain copy");
        PrTimelineEvent labeled = new PrTimelineEvent(2L, "labeled", "bob", commentedAt, null, Optional.empty());
        PrTimelineEvent issueCopy = comment(3L, "alice", commentedAt, "copy with reactions");
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw(null));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(List.of());
        when(gitHub.fetchPrTimeline("pat", REF, null)).thenReturn(List.of(timelineCopy, labeled));
        when(gitHub.fetchPrIssueComments("pat", REF, null)).thenReturn(List.of(issueCopy));

        StoredPrDetail stored = fetcher.fetch("pat", REF);

        assertThat(stored.timeline()).containsExactly(labeled, issueCopy);
    }

    @Test
    void refetchesTheFullMutableTimeline()
    {
        when(gitHub.fetchPrDetail("pat", REF)).thenReturn(raw(null));
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(List.of());
        when(gitHub.fetchPrTimeline("pat", REF, null)).thenReturn(List.of());
        when(gitHub.fetchPrIssueComments("pat", REF, null)).thenReturn(List.of());

        fetcher.fetch("pat", REF);

        verify(gitHub).fetchPrTimeline("pat", REF, null);
        verify(gitHub).fetchPrIssueComments("pat", REF, null);
    }

    private static PrRawDetail raw(String headSha)
    {
        return new PrRawDetail(
                "body", List.of(), false, true, "clean", 4, 2, 1, 0, List.of(),
                headSha, "feature");
    }

    private static PrTimelineEvent comment(Long githubId, String actor, Instant timestamp, String body)
    {
        return new PrTimelineEvent(githubId, "commented", actor, timestamp, body, Optional.empty());
    }
}
