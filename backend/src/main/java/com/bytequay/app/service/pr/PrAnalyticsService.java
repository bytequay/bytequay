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

import com.bytequay.app.domain.PrAnalyticsSummary;
import com.bytequay.app.domain.PrAnalyticsSummary.KpiCard;
import com.bytequay.app.domain.PrAnalyticsSummary.StaleAuthoredPr;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Aggregates the local PR cache into the figures the Analytics page
 * renders. Only the KPIs that can be sourced honestly from data we
 * already hold are computed here; richer surfaces (daily activity
 * bars, donut breakdown, review-thread mirror) wait for the dedicated
 * mirror tables described in {@code docs/mockups/activity-design.md}.
 *
 * <p>All reads are local — no GitHub calls, no PAT required.
 */
@Service
public class PrAnalyticsService
{
    private static final int STALE_THRESHOLD_DAYS = 7;
    private static final int STALE_MAX_ROWS = 20;

    private final PullRequestStore pullRequestStore;
    private final PrDetailStore detailStore;
    private final WatchedRepoStore watchedRepoStore;
    private final AppSettingsStore settingsStore;

    public PrAnalyticsService(
            PullRequestStore pullRequestStore,
            PrDetailStore detailStore,
            WatchedRepoStore watchedRepoStore,
            AppSettingsStore settingsStore)
    {
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
    }

    public PrAnalyticsSummary summarize(String rawScope)
    {
        String scope = normalizeScope(rawScope);
        Instant cutoff = cutoffFor(scope);
        int watchedCount = watchedRepoStore.findAll().size();
        String currentLogin = settingsStore.get(AppSettingsStore.Key.GITHUB_LOGIN).orElse(null);

        List<PullRequest> all = pullRequestStore.findAll();

        ReviewAggregate reviewAggregate = currentLogin == null
                ? ReviewAggregate.empty()
                : aggregateReviews(all, currentLogin, cutoff);

        KpiCard prsReviewed = new KpiCard(
                (double) reviewAggregate.prCount,
                formatCount(reviewAggregate.prCount),
                true,
                null);
        KpiCard approvalRate = reviewAggregate.prCount == 0
                ? new KpiCard(null, "—", true, null)
                : new KpiCard(
                        reviewAggregate.approvalRate(),
                        formatPercent(reviewAggregate.approvalRate()),
                        true,
                        null);
        KpiCard linesReviewed = new KpiCard(
                (double) reviewAggregate.linesReviewed,
                formatCount(reviewAggregate.linesReviewed),
                true,
                null);
        // No review-event timestamps in the local store yet — placeholder
        // until the review mirror lands; see Phase 3 backend section in
        // docs/mockups/activity-design.md.
        KpiCard responseToReviewRequest = new KpiCard(null, "—", true, "Pending review mirror");

        List<StaleAuthoredPr> stale = staleAuthoredPrs(all, currentLogin);

        return new PrAnalyticsSummary(
                scope,
                watchedCount,
                currentLogin,
                prsReviewed,
                approvalRate,
                linesReviewed,
                responseToReviewRequest,
                stale);
    }

    private static String normalizeScope(String raw)
    {
        if (raw == null) {
            return "30d";
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "7d", "30d", "90d", "all" -> normalized;
            default -> "30d";
        };
    }

    private static Instant cutoffFor(String scope)
    {
        Instant now = Instant.now();
        return switch (scope) {
            case "7d" -> now.minus(Duration.ofDays(7));
            case "30d" -> now.minus(Duration.ofDays(30));
            case "90d" -> now.minus(Duration.ofDays(90));
            default -> Instant.EPOCH;
        };
    }

    private ReviewAggregate aggregateReviews(List<PullRequest> all, String currentLogin, Instant cutoff)
    {
        int prCount = 0;
        int approved = 0;
        long lines = 0L;
        for (PullRequest pr : all) {
            // PrReviewState has no submitted_at — use the PR row's
            // updatedAt as a proxy so the scope filter still constrains
            // the count. Honest under-count for old reviews on PRs that
            // got re-touched recently; the "What's measured here" card
            // calls that out.
            Instant when = pr.updatedAt();
            if (cutoff != Instant.EPOCH && (when == null || when.isBefore(cutoff))) {
                continue;
            }
            Optional<StoredPrDetail> detailOpt = detailStore.find(pr.id());
            if (detailOpt.isEmpty()) {
                continue;
            }
            StoredPrDetail detail = detailOpt.get();
            String latestVerdict = latestVerdictBy(detail.reviews(), currentLogin);
            if (latestVerdict == null) {
                continue;
            }
            prCount++;
            if ("APPROVED".equalsIgnoreCase(latestVerdict)) {
                approved++;
            }
            if (detail.raw() != null) {
                lines += (long) Math.max(0, detail.raw().additions()) + (long) Math.max(0, detail.raw().deletions());
            }
        }
        return new ReviewAggregate(prCount, approved, lines);
    }

    private static String latestVerdictBy(List<PrReviewState> reviews, String login)
    {
        if (reviews == null || reviews.isEmpty()) {
            return null;
        }
        // Reviews are stored in submission order — the last entry for
        // {@code login} is the most recent verdict. Pure-comment
        // reviews (state = "COMMENTED") still count as "I reviewed
        // this PR" but never as an approval.
        String latest = null;
        for (PrReviewState review : reviews) {
            if (review.login() != null && review.login().equalsIgnoreCase(login)) {
                latest = review.state();
            }
        }
        return latest;
    }

    private List<StaleAuthoredPr> staleAuthoredPrs(List<PullRequest> all, String currentLogin)
    {
        if (currentLogin == null) {
            return ImmutableList.of();
        }
        Instant staleBefore = Instant.now().minus(Duration.ofDays(STALE_THRESHOLD_DAYS));
        ImmutableList.Builder<StaleAuthoredPr> rows = ImmutableList.builder();
        all.stream()
                .filter(pr -> pr.origin() == PullRequest.Origin.AUTHORED)
                .filter(pr -> !pr.draft())
                .filter(pr -> pr.createdAt() != null)
                .filter(pr -> pr.createdAt().isBefore(staleBefore))
                .filter(PrAnalyticsService::isOpen)
                .sorted(Comparator.comparing(PullRequest::createdAt))
                .limit(STALE_MAX_ROWS)
                .forEach(pr -> {
                    int ageDays = (int) Duration.between(pr.createdAt(), Instant.now()).toDays();
                    rows.add(new StaleAuthoredPr(pr.id(), pr.repo(), pr.number(), pr.title(), pr.createdAt(), ageDays));
                });
        return rows.build();
    }

    private static boolean isOpen(PullRequest pr)
    {
        if (pr.state() != null) {
            return "open".equalsIgnoreCase(pr.state());
        }
        // Legacy rows without state — fall back to closed-at / merged-at.
        return pr.closedAt() == null && pr.mergedAt() == null;
    }

    private static String formatCount(long n)
    {
        if (n < 1000) {
            return Long.toString(n);
        }
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String formatPercent(double fraction)
    {
        return Math.round(fraction * 100) + "%";
    }

    private record ReviewAggregate(int prCount, int approved, long linesReviewed)
    {
        static ReviewAggregate empty()
        {
            return new ReviewAggregate(0, 0, 0L);
        }

        double approvalRate()
        {
            return prCount == 0 ? 0.0 : (double) approved / (double) prCount;
        }
    }
}
