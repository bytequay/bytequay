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
import com.bytequay.app.domain.PrAnalyticsSummary.CoReviewer;
import com.bytequay.app.domain.PrAnalyticsSummary.DailyActivity;
import com.bytequay.app.domain.PrAnalyticsSummary.HeatmapCell;
import com.bytequay.app.domain.PrAnalyticsSummary.KpiCard;
import com.bytequay.app.domain.PrAnalyticsSummary.OutcomeSlice;
import com.bytequay.app.domain.PrAnalyticsSummary.RepoReviewCount;
import com.bytequay.app.domain.PrAnalyticsSummary.SizeBucket;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
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
    private static final int REPOS_MAX_ROWS = 8;
    private static final int NETWORK_MAX_ROWS = 8;
    // Cap daily-bar history for "all" scope so the chart doesn't try to
    // render 5 years of one-pixel columns.
    private static final int DAILY_MAX_DAYS_ALL = 90;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final List<String> OUTCOME_ORDER = ImmutableList.of(
            "APPROVED", "CHANGES_REQUESTED", "COMMENTED", "DISMISSED");
    // Tuned to land most everyday changes in Small / Medium and reserve
    // the tail buckets for "this PR needs a meeting" outliers. Edges
    // inclusive on the upper bound — a 99-line PR lands in Small.
    private static final List<SizeBucketDef> SIZE_BUCKETS = ImmutableList.of(
            new SizeBucketDef("Tiny", 0, 9),
            new SizeBucketDef("Small", 10, 99),
            new SizeBucketDef("Medium", 100, 499),
            new SizeBucketDef("Large", 500, 999),
            new SizeBucketDef("Huge", 1000, Integer.MAX_VALUE));

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
                reviewAggregate.outcomes(),
                reviewAggregate.sizeDistribution(),
                reviewAggregate.reposByReview(),
                reviewAggregate.dailyActivity(),
                reviewAggregate.reviewHeatmap(),
                reviewAggregate.reviewNetwork(),
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
        ZoneId zone = ZoneId.systemDefault();
        int prCount = 0;
        int approved = 0;
        long lines = 0L;
        Map<String, Integer> outcomeCounts = new HashMap<>();
        int[] sizeCounts = new int[SIZE_BUCKETS.size()];
        Map<String, Integer> repoCounts = new HashMap<>();
        // Daily buckets and heatmap are sparse — we only know about
        // reviews whose detail blob already carried submitted_at. The
        // map fills in naturally as new syncs land.
        Map<LocalDate, int[]> dailyCounts = new HashMap<>();
        int[][] heatmap = new int[7][24];
        Map<String, Integer> coReviewerCounts = new HashMap<>();
        for (PullRequest pr : all) {
            Optional<StoredPrDetail> detailOpt = detailStore.find(pr.id());
            if (detailOpt.isEmpty()) {
                continue;
            }
            StoredPrDetail detail = detailOpt.get();
            PrReviewState latestReview = latestReviewBy(detail.reviews(), currentLogin);
            if (latestReview == null) {
                continue;
            }
            // Use the review's own timestamp when V53+ captured it; fall
            // back to the PR's updatedAt for legacy rows so the time-
            // scope filter still constrains them. Skipping those rows
            // entirely would silently shrink the cards every time the
            // user picks a narrower scope.
            Instant when = latestReview.submittedAt() != null ? latestReview.submittedAt() : pr.updatedAt();
            if (cutoff != Instant.EPOCH && (when == null || when.isBefore(cutoff))) {
                continue;
            }
            String latestVerdict = latestReview.state();
            if (latestVerdict == null) {
                continue;
            }
            prCount++;
            if ("APPROVED".equalsIgnoreCase(latestVerdict)) {
                approved++;
            }
            outcomeCounts.merge(latestVerdict.toUpperCase(Locale.ROOT), 1, Integer::sum);
            int prLines = 0;
            if (detail.raw() != null) {
                prLines = Math.max(0, detail.raw().additions()) + Math.max(0, detail.raw().deletions());
                lines += prLines;
            }
            sizeCounts[bucketIndex(prLines)]++;
            if (pr.repo() != null) {
                repoCounts.merge(pr.repo(), 1, Integer::sum);
            }

            // Daily-bars and heatmap need per-review timestamps. Walk
            // every review by the current user on this PR (not just the
            // latest verdict) and bucket each into the day/hour grid.
            for (PrReviewState review : detail.reviews()) {
                if (review.login() == null || !review.login().equalsIgnoreCase(currentLogin)) {
                    continue;
                }
                Instant submitted = review.submittedAt();
                if (submitted == null) {
                    continue;
                }
                if (cutoff != Instant.EPOCH && submitted.isBefore(cutoff)) {
                    continue;
                }
                ZonedDateTime local = submitted.atZone(zone);
                LocalDate day = local.toLocalDate();
                int stateIdx = dailyStateIndex(review.state());
                if (stateIdx >= 0) {
                    dailyCounts.computeIfAbsent(day, k -> new int[4])[stateIdx]++;
                }
                int dow = local.getDayOfWeek().getValue() % 7;
                heatmap[dow][local.getHour()]++;
            }

            // Network: distinct other-reviewer logins on PRs you touched.
            // Use a per-PR Set so two reviews by the same person don't
            // double-count one PR. Bounded to the active scope by the
            // outer `when` check above.
            Set<String> coReviewers = new HashSet<>();
            for (PrReviewState review : detail.reviews()) {
                if (review.login() == null) {
                    continue;
                }
                if (review.login().equalsIgnoreCase(currentLogin)) {
                    continue;
                }
                coReviewers.add(review.login().toLowerCase(Locale.ROOT));
            }
            for (String coReviewer : coReviewers) {
                coReviewerCounts.merge(coReviewer, 1, Integer::sum);
            }
        }
        return new ReviewAggregate(
                prCount,
                approved,
                lines,
                outcomeSlices(outcomeCounts),
                sizeBuckets(sizeCounts),
                topRepos(repoCounts),
                buildDailyActivity(dailyCounts, cutoff, zone),
                buildHeatmap(heatmap),
                topCoReviewers(coReviewerCounts));
    }

    private static int dailyStateIndex(String state)
    {
        if (state == null) {
            return -1;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "APPROVED" -> 0;
            case "CHANGES_REQUESTED" -> 1;
            case "COMMENTED" -> 2;
            case "DISMISSED" -> 3;
            default -> -1;
        };
    }

    private static List<DailyActivity> buildDailyActivity(
            Map<LocalDate, int[]> counts,
            Instant cutoff,
            ZoneId zone)
    {
        if (counts.isEmpty()) {
            return ImmutableList.of();
        }
        LocalDate today = LocalDate.now(zone);
        LocalDate from;
        if (cutoff == Instant.EPOCH) {
            // "all" — cap to a manageable window so the renderer doesn't
            // try to lay out years of empty days.
            from = today.minusDays(DAILY_MAX_DAYS_ALL - 1L);
        }
        else {
            from = cutoff.atZone(zone).toLocalDate();
        }
        ImmutableList.Builder<DailyActivity> out = ImmutableList.builder();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            int[] cell = counts.getOrDefault(day, new int[4]);
            out.add(new DailyActivity(day.format(ISO_DATE), cell[0], cell[1], cell[2], cell[3]));
        }
        return out.build();
    }

    private static List<HeatmapCell> buildHeatmap(int[][] grid)
    {
        ImmutableList.Builder<HeatmapCell> out = ImmutableList.builder();
        int total = 0;
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                int count = grid[d][h];
                total += count;
                if (count > 0) {
                    out.add(new HeatmapCell(d, h, count));
                }
            }
        }
        // Empty grid → return empty so the renderer shows its empty
        // state. We don't ship 168 zero cells just to make the JSON
        // shape uniform.
        return total == 0 ? ImmutableList.of() : out.build();
    }

    private static List<CoReviewer> topCoReviewers(Map<String, Integer> counts)
    {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(NETWORK_MAX_ROWS)
                .map(e -> new CoReviewer(e.getKey(), e.getValue()))
                .collect(toImmutableList());
    }

    private static int bucketIndex(int lines)
    {
        for (int i = 0; i < SIZE_BUCKETS.size(); i++) {
            SizeBucketDef bucket = SIZE_BUCKETS.get(i);
            if (lines >= bucket.lo && lines <= bucket.hi) {
                return i;
            }
        }
        // Defensive: negative numbers (shouldn't occur — additions /
        // deletions are clamped to >= 0 above) fall into Tiny.
        return 0;
    }

    private static List<OutcomeSlice> outcomeSlices(Map<String, Integer> counts)
    {
        ImmutableList.Builder<OutcomeSlice> out = ImmutableList.builder();
        for (String state : OUTCOME_ORDER) {
            out.add(new OutcomeSlice(state, counts.getOrDefault(state, 0)));
        }
        // Any unusual states (e.g. PENDING) get a trailing slice so
        // they don't disappear silently; the renderer can fold them
        // into an "Other" wedge.
        counts.forEach((state, count) -> {
            if (!OUTCOME_ORDER.contains(state)) {
                out.add(new OutcomeSlice(state, count));
            }
        });
        return out.build();
    }

    private static List<SizeBucket> sizeBuckets(int[] counts)
    {
        ImmutableList.Builder<SizeBucket> out = ImmutableList.builder();
        for (int i = 0; i < SIZE_BUCKETS.size(); i++) {
            out.add(new SizeBucket(SIZE_BUCKETS.get(i).label, counts[i]));
        }
        return out.build();
    }

    private static List<RepoReviewCount> topRepos(Map<String, Integer> counts)
    {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(REPOS_MAX_ROWS)
                .map(e -> new RepoReviewCount(e.getKey(), e.getValue()))
                .collect(toImmutableList());
    }

    private static PrReviewState latestReviewBy(List<PrReviewState> reviews, String login)
    {
        if (reviews == null || reviews.isEmpty()) {
            return null;
        }
        // Reviews are stored in submission order — the last entry for
        // {@code login} is the most recent verdict. Pure-comment
        // reviews (state = "COMMENTED") still count as "I reviewed
        // this PR" but never as an approval.
        PrReviewState latest = null;
        for (PrReviewState review : reviews) {
            if (review.login() != null && review.login().equalsIgnoreCase(login)) {
                latest = review;
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

    private record ReviewAggregate(
            int prCount,
            int approved,
            long linesReviewed,
            List<OutcomeSlice> outcomes,
            List<SizeBucket> sizeDistribution,
            List<RepoReviewCount> reposByReview,
            List<DailyActivity> dailyActivity,
            List<HeatmapCell> reviewHeatmap,
            List<CoReviewer> reviewNetwork)
    {
        static ReviewAggregate empty()
        {
            return new ReviewAggregate(
                    0,
                    0,
                    0L,
                    outcomeSlices(new HashMap<>()),
                    sizeBuckets(new int[SIZE_BUCKETS.size()]),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of());
        }

        double approvalRate()
        {
            return prCount == 0 ? 0.0 : (double) approved / (double) prCount;
        }
    }

    private record SizeBucketDef(String label, int lo, int hi) {}
}
