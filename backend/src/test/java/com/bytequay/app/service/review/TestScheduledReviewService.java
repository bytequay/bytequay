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

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.scheduler.QuietHoursPolicy;
import com.bytequay.app.service.threads.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestScheduledReviewService
{
    private AppSettingsStore appSettings;
    private PullRequestStore pullRequestStore;
    private ReviewStore reviewStore;
    private ReviewPassService reviewPassService;
    private NotificationService notifications;
    private ObjectMapper mapper;
    private QuietHoursPolicy quietHours;
    private ScheduledReviewService service;

    @BeforeEach
    void setUp()
    {
        appSettings = mock(AppSettingsStore.class);
        pullRequestStore = mock(PullRequestStore.class);
        reviewStore = mock(ReviewStore.class);
        reviewPassService = mock(ReviewPassService.class);
        notifications = mock(NotificationService.class);
        mapper = new ObjectMapper();
        quietHours = mock(QuietHoursPolicy.class);
        when(quietHours.isQuietNow()).thenReturn(false);
        service = new ScheduledReviewService(
                appSettings, pullRequestStore, reviewStore,
                reviewPassService, notifications, mapper, quietHours);
    }

    @Test
    void doesNothingWhenDisabled()
    {
        // Default-disabled state — Settings has no row, isEnabled
        // resolves to false, and the loop must not touch any other
        // collaborator. CLAUDE.md: automation never runs without an
        // explicit opt-in.
        when(appSettings.get(Key.SCHEDULED_REVIEWS_ENABLED)).thenReturn(Optional.empty());

        service.runScheduledReviews();

        verifyNoInteractions(pullRequestStore, reviewStore, reviewPassService, notifications);
    }

    @Test
    void picksUpReviewRequestedPrsWhenEnabledAndEmitsNotificationOnEachPass()
    {
        when(appSettings.get(Key.SCHEDULED_REVIEWS_ENABLED)).thenReturn(Optional.of("true"));
        when(pullRequestStore.findAll()).thenReturn(List.of(
                pr(1L, "acme/widget", 42, PullRequest.Origin.REVIEW_REQUESTED, "open"),
                pr(2L, "acme/widget", 43, PullRequest.Origin.REVIEW_REQUESTED, "open"),
                // AUTHORED PRs should be ignored — only review-requested
                // ones land on the panel.
                pr(3L, "acme/widget", 44, PullRequest.Origin.AUTHORED, "open"),
                // Closed PRs should be ignored — no point reviewing a
                // merged PR retroactively.
                pr(4L, "acme/widget", 45, PullRequest.Origin.REVIEW_REQUESTED, "closed")));
        when(reviewStore.listPassesForPr(anyString(), anyInt())).thenReturn(List.of());
        when(reviewPassService.startReviewOnPr(eq("acme/widget"), eq(42)))
                .thenReturn(detail("pass-42", "thread-42", ReviewPhase.ARBITRATE));
        when(reviewPassService.startReviewOnPr(eq("acme/widget"), eq(43)))
                .thenReturn(detail("pass-43", "thread-43", ReviewPhase.TERMINATE));

        service.runScheduledReviews();

        verify(reviewPassService).startReviewOnPr("acme/widget", 42);
        verify(reviewPassService).startReviewOnPr("acme/widget", 43);
        verify(reviewPassService, never()).startReviewOnPr(eq("acme/widget"), eq(44));
        verify(reviewPassService, never()).startReviewOnPr(eq("acme/widget"), eq(45));

        // Each headless pass parks by outcome: the ARBITRATE pass
        // (disputed remain) → NEEDS_ATTENTION; the TERMINATE pass (all
        // agreed) → AWAITING_REVIEW. Both payloads carry
        // source=scheduled-review so the UI routes the click to the
        // review-thread page and surfaces them on the auto* filter.
        ArgumentCaptor<String> needsAttn = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifyNeedsAttention(eq("thread-42"), any(), needsAttn.capture());
        ArgumentCaptor<String> awaiting = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifyAwaitingReview(eq("thread-43"), any(), awaiting.capture());
        assertThat(needsAttn.getValue()).contains("\"source\":\"scheduled-review\"");
        assertThat(awaiting.getValue()).contains("\"source\":\"scheduled-review\"");
    }

    @Test
    void dedupsAgainstAPassFromWithinTheLastDay()
    {
        when(appSettings.get(Key.SCHEDULED_REVIEWS_ENABLED)).thenReturn(Optional.of("true"));
        when(pullRequestStore.findAll()).thenReturn(List.of(
                pr(1L, "acme/widget", 42, PullRequest.Origin.REVIEW_REQUESTED, "open")));
        when(reviewStore.listPassesForPr("acme/widget", 42)).thenReturn(List.of(
                existingPass(Instant.now().minusSeconds(60 * 30)))); // 30 min ago

        service.runScheduledReviews();

        // PR has a recent pass; we don't re-bill the LLM for it.
        verify(reviewPassService, never()).startReviewOnPr(anyString(), anyInt());
        verifyNoInteractions(notifications);
    }

    @Test
    void runsAgainWhenThePriorPassIsOlderThanTheDedupWindow()
    {
        when(appSettings.get(Key.SCHEDULED_REVIEWS_ENABLED)).thenReturn(Optional.of("true"));
        when(pullRequestStore.findAll()).thenReturn(List.of(
                pr(1L, "acme/widget", 42, PullRequest.Origin.REVIEW_REQUESTED, "open")));
        when(reviewStore.listPassesForPr("acme/widget", 42)).thenReturn(List.of(
                existingPass(Instant.now().minus(Duration.ofDays(2)))));
        when(reviewPassService.startReviewOnPr(eq("acme/widget"), eq(42)))
                .thenReturn(detail("pass-42", "thread-42", ReviewPhase.TERMINATE));

        service.runScheduledReviews();

        verify(reviewPassService).startReviewOnPr("acme/widget", 42);
    }

    @Test
    void oneBadPrDoesNotTakeDownTheRestOfTheLoop()
    {
        when(appSettings.get(Key.SCHEDULED_REVIEWS_ENABLED)).thenReturn(Optional.of("true"));
        when(pullRequestStore.findAll()).thenReturn(List.of(
                pr(1L, "acme/widget", 42, PullRequest.Origin.REVIEW_REQUESTED, "open"),
                pr(2L, "acme/widget", 43, PullRequest.Origin.REVIEW_REQUESTED, "open")));
        when(reviewStore.listPassesForPr(anyString(), anyInt())).thenReturn(List.of());
        when(reviewPassService.startReviewOnPr(eq("acme/widget"), eq(42)))
                .thenThrow(new RuntimeException("Anthropic returned 529"));
        when(reviewPassService.startReviewOnPr(eq("acme/widget"), eq(43)))
                .thenReturn(detail("pass-43", "thread-43", ReviewPhase.TERMINATE));

        service.runScheduledReviews();

        // The first PR threw; the loop should keep going and the
        // second PR's review should still land + notify.
        verify(reviewPassService).startReviewOnPr("acme/widget", 42);
        verify(reviewPassService).startReviewOnPr("acme/widget", 43);
        verify(notifications, times(1)).notifyAwaitingReview(eq("thread-43"), any(), anyString());
    }

    @Test
    void setEnabledWritesThroughToAppSettings()
    {
        service.setEnabled(true);
        verify(appSettings).set(Key.SCHEDULED_REVIEWS_ENABLED, "true");

        service.setEnabled(false);
        verify(appSettings).set(Key.SCHEDULED_REVIEWS_ENABLED, "false");
    }

    private static PullRequest pr(long id, String repo, int number,
            PullRequest.Origin origin, String state)
    {
        return new PullRequest(
                id, repo, number, "title", "alice",
                "https://github.com/" + repo + "/pull/" + number,
                Instant.parse("2026-05-22T12:00:00Z"),
                Instant.parse("2026-05-22T12:00:00Z"),
                origin,
                List.of(), Map.of(), /* draft */ false,
                /* viewedAt */ null, /* reviewedAt */ null,
                /* handledAction */ null,
                List.of(),
                /* ciStatus */ null,
                /* additions */ 0, /* deletions */ 0, /* commentCount */ 0,
                /* attentionReason */ null,
                state, null, null, null, null, null,
                Map.of(),
                null, null, "feature/" + number);
    }

    private static ReviewPass existingPass(Instant createdAt)
    {
        return new ReviewPass(
                "pass-existing", "thread-existing",
                "acme/widget", 42, /* headSha */ "abc",
                ReviewPhase.TERMINATE,
                0, 3, 500L, 0L, null,
                createdAt, createdAt);
    }

    @Test
    void stopsTheRunOnceTheRollingDailyCostCapIsHit()
    {
        when(appSettings.get(Key.SCHEDULED_REVIEWS_ENABLED)).thenReturn(Optional.of("true"));
        when(pullRequestStore.findAll()).thenReturn(List.of(
                pr(1L, "acme/widget", 42, PullRequest.Origin.REVIEW_REQUESTED, "open"),
                pr(2L, "acme/widget", 43, PullRequest.Origin.REVIEW_REQUESTED, "open")));
        when(reviewStore.listPassesForPr(anyString(), anyInt())).thenReturn(List.of());
        // $4.50 already spent in the trailing 24h; PR 42's pass costs
        // $0.60 → $5.10 ≥ the $5/day cap, so PR 43 is deferred.
        when(reviewStore.sumPassCostSince(any())).thenReturn(4_500L);
        when(reviewPassService.startReviewOnPr(eq("acme/widget"), eq(42)))
                .thenReturn(costedDetail("pass-42", "thread-42", 600L));

        service.runScheduledReviews();

        verify(reviewPassService).startReviewOnPr("acme/widget", 42);
        verify(reviewPassService, never()).startReviewOnPr(eq("acme/widget"), eq(43));
    }

    private static ReviewPassDetail detail(String passId, String threadId, ReviewPhase phase)
    {
        ReviewPass pass = new ReviewPass(
                passId, threadId, "acme/widget", 42,
                /* headSha */ "abc",
                phase,
                0, 3, 500L, 0L, null,
                Instant.now(), phase == ReviewPhase.ARBITRATE ? null : Instant.now());
        ReviewFinding finding = new ReviewFinding(
                "f1", passId, "src/x.ts", 1,
                ReviewFindingSeverity.NIT,
                ReviewFindingStatus.AGREED,
                "note", null, null, Instant.now());
        return new ReviewPassDetail(pass, null, List.of(), List.of(), List.of(finding));
    }

    private static ReviewPassDetail costedDetail(String passId, String threadId, long costMilli)
    {
        ReviewPass pass = new ReviewPass(
                passId, threadId, "acme/widget", 42, "abc", ReviewPhase.TERMINATE,
                0, 3, 500L, costMilli, null, Instant.now(), Instant.now());
        return new ReviewPassDetail(pass, null, List.of(), List.of(), List.of());
    }
}
