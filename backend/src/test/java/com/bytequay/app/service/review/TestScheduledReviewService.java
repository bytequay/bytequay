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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.InvestigationReviewData;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.scheduler.QuietHoursPolicy;
import com.bytequay.app.service.localpr.PRSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestScheduledReviewService
{
    private AppSettingsStore settings;
    private PullRequestStore pullRequests;
    private PRSyncService sync;
    private InvestigationReviewService reviews;
    private InvestigationReviewStore reviewStore;
    private WorkspaceStore workspaces;
    private QuietHoursPolicy quietHours;
    private ScheduledReviewService service;

    @BeforeEach
    void setUp()
    {
        settings = mock(AppSettingsStore.class);
        pullRequests = mock(PullRequestStore.class);
        sync = mock(PRSyncService.class);
        reviews = mock(InvestigationReviewService.class);
        reviewStore = mock(InvestigationReviewStore.class);
        workspaces = mock(WorkspaceStore.class);
        quietHours = mock(QuietHoursPolicy.class);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(workspaces.listWorkspaces()).thenReturn(List.of());
        service = new ScheduledReviewService(
                settings, pullRequests, sync, reviews, reviewStore, workspaces, quietHours);
    }

    @Test
    void doesNothingUntilUserEnablesIt()
    {
        when(settings.get(Key.SCHEDULED_REVIEWS_ENABLED)).thenReturn(Optional.empty());
        service.runScheduledReviews();
        verifyNoInteractions(pullRequests, sync, reviews, reviewStore, workspaces);
    }

    @Test
    void startsWorkspaceOwnedAgentReviewForEligiblePr()
    {
        enable();
        PullRequest candidate = candidate(42, PullRequest.Origin.REVIEW_REQUESTED, "open");
        PR pr = externalPr(42);
        when(pullRequests.findAll()).thenReturn(List.of(candidate));
        when(sync.syncExternalPR("acme/widget", 42)).thenReturn(Optional.of(pr));
        when(reviews.findByPr(pr.id())).thenReturn(Optional.empty());

        service.runScheduledReviews();

        verify(reviews).start(eq(pr.id()), any(InvestigationReviewService.StartOptions.class));
    }

    @Test
    void staleReviewGetsDeltaRoundWhileCurrentReviewIsReused()
    {
        enable();
        PullRequest staleCandidate = candidate(42, PullRequest.Origin.REVIEW_REQUESTED, "open");
        PullRequest currentCandidate = candidate(43, PullRequest.Origin.REVIEW_REQUESTED, "open");
        PR stalePr = externalPr(42);
        PR currentPr = externalPr(43);
        when(pullRequests.findAll()).thenReturn(List.of(staleCandidate, currentCandidate));
        when(sync.syncExternalPR("acme/widget", 42)).thenReturn(Optional.of(stalePr));
        when(sync.syncExternalPR("acme/widget", 43)).thenReturn(Optional.of(currentPr));
        when(reviews.findByPr(stalePr.id())).thenReturn(Optional.of(review("review-42", "STALE")));
        when(reviews.findByPr(currentPr.id())).thenReturn(Optional.of(review("review-43", "ACTIVE")));

        service.runScheduledReviews();

        verify(reviews).createRound(eq("review-42"), eq("re-review"), eq(List.of()),
                any(InvestigationReviewService.StartOptions.class));
        verify(reviews, never()).createRound(eq("review-43"), any(), any(), any());
    }

    @Test
    void filtersOwnAndClosedPrsAndRespectsDailyReservedBudget()
    {
        enable();
        when(pullRequests.findAll()).thenReturn(List.of(
                candidate(42, PullRequest.Origin.AUTHORED, "open"),
                candidate(43, PullRequest.Origin.REVIEW_REQUESTED, "closed"),
                candidate(44, PullRequest.Origin.REVIEW_REQUESTED, "open")));
        when(reviewStore.sumRoundCostCentsSince(any())).thenReturn(500L);

        service.runScheduledReviews();

        verifyNoInteractions(sync, reviews);
    }

    @Test
    void setEnabledPersistsTheOptIn()
    {
        service.setEnabled(true);
        verify(settings).set(Key.SCHEDULED_REVIEWS_ENABLED, "true");
    }

    private void enable()
    {
        when(settings.get(Key.SCHEDULED_REVIEWS_ENABLED)).thenReturn(Optional.of("true"));
    }

    private static InvestigationReviewData review(String id, String status)
    {
        AgentReviewRow review = new AgentReviewRow(
                id, "acme/widget", "pr-42", "base", "head", status,
                "ws-default", "thread-1", null);
        ReviewRoundRow round = new ReviewRoundRow(
                "round-1", id, "run-1", "initial", "full", "head", "head",
                "COMPLETED", new RoundBudget(50, 10), 2, ReviewCapabilities.remoteOnly(), null);
        return new InvestigationReviewData(
                review, List.of(round), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static PR externalPr(int number)
    {
        return PR.createExternal(
                "pr-" + number, "acme/widget", number,
                "https://github.com/acme/widget/pull/" + number, "alice",
                "feature/" + number, "main", "title", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-14T00:00:00Z"), null, null);
    }

    private static PullRequest candidate(int number, PullRequest.Origin origin, String state)
    {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new PullRequest(
                number, "acme/widget", number, "title", "alice",
                "https://github.com/acme/widget/pull/" + number, now, now, origin,
                List.of(), Map.of(), false, null, null, null, List.of(), null,
                0, 0, 0, null, state, null, null, null, null, null,
                Map.of(), null, null, "feature/" + number);
    }
}
