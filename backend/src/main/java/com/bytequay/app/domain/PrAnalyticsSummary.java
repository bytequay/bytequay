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
package com.bytequay.app.domain;

import java.time.Instant;
import java.util.List;

/**
 * Single payload behind {@code GET /prs/analytics}. Holds the KPIs the
 * page renders today plus the scope chip values so the UI can label
 * partial data honestly. KPI cards whose underlying data lives only in
 * the cached PR-detail blob carry {@code partial = true}; the
 * "Response to review request" card is a placeholder until the
 * review-mirror lands and currently surfaces {@code pendingNote}.
 *
 * @param scope query scope echoed back to the UI: {@code "7d"},
 * {@code "30d"}, {@code "90d"}, or {@code "all"}.
 * @param watchedRepoCount number of repos the user has explicitly watched.
 * @param currentLogin GitHub login of the current user, or null when not yet
 * known.
 * @param reviewOutcomes latest-verdict distribution across PRs you reviewed.
 * @param sizeDistribution count of reviewed PRs bucketed by total line change.
 * @param reposByReview top repos by number of PRs reviewed.
 * @param dailyActivity one bucket per calendar day in the active scope window.
 * @param reviewHeatmap 7 by 24 heatmap of review counts, bucketed in the
 * user's local timezone.
 * @param reviewNetwork top co-reviewers across PRs you also reviewed.
 * @param staleAuthoredPrs open PRs you authored that have not been touched in
 * more than seven days.
 */
public record PrAnalyticsSummary(
        String scope,
        int watchedRepoCount,
        String currentLogin,
        KpiCard prsReviewed,
        KpiCard approvalRate,
        KpiCard linesReviewed,
        KpiCard responseToReviewRequest,
        List<OutcomeSlice> reviewOutcomes,
        List<SizeBucket> sizeDistribution,
        List<RepoReviewCount> reposByReview,
        List<DailyActivity> dailyActivity,
        List<HeatmapCell> reviewHeatmap,
        List<CoReviewer> reviewNetwork,
        List<StaleAuthoredPr> staleAuthoredPrs)
{
    /**
     * Wire shape for one KPI card. {@code value} is the raw scalar
     * (count or ratio); {@code displayValue} is the rendered string
     * (e.g. "12", "87%", "1,240"). Partial cards depend on cached
     * detail data and may under-count.
     *
     * @param pendingNote when non-null, the card renders an empty state with
     * this copy instead of the value.
     */
    public record KpiCard(
            Double value,
            String displayValue,
            boolean partial,
            String pendingNote) {}

    /**
     * One slice of the review-outcomes donut. {@code state} is the
     * canonical GitHub review state ("APPROVED", "CHANGES_REQUESTED",
     * "COMMENTED", "DISMISSED"); {@code count} is the number of PRs
     * whose latest verdict from the current user was this state.
     */
    public record OutcomeSlice(String state, int count) {}

    /**
     * One bar in the size-distribution chart. {@code label} is a
     * human-readable bucket name ("Tiny", "Small", ...); {@code count}
     * is the number of PRs you reviewed that fell in the bucket.
     */
    public record SizeBucket(String label, int count) {}

    /**
     * One row in the "Repos by review activity" panel. {@code repo}
     * is the {@code owner/name} pair; {@code count} is the number of
     * PRs you reviewed in that repo within the active scope.
     */
    public record RepoReviewCount(String repo, int count) {}

    /**
     * One bucket of the daily-activity chart. {@code date} is ISO-8601
     * yyyy-MM-dd in the user's local timezone; the four count fields
     * stack to make the day's total.
     */
    public record DailyActivity(
            String date,
            int approved,
            int changesRequested,
            int commented,
            int dismissed) {}

    /**
     * One cell of the day-of-week × hour-of-day heatmap. {@code
     * dayOfWeek} is 0 (Sunday) through 6 (Saturday); {@code hour} is
     * 0–23 in the user's local timezone.
     */
    public record HeatmapCell(int dayOfWeek, int hour, int count) {}

    /**
     * One row in the review-network panel. {@code login} is the
     * GitHub login of another reviewer; {@code count} is the number
     * of distinct PRs you both reviewed within the active scope.
     */
    public record CoReviewer(String login, int count) {}

    /**
     * One row in the stale-PRs card. {@code ageDays} is the integer
     * day count from {@link #createdAt} to "now" at the moment of the
     * request.
     */
    public record StaleAuthoredPr(
            long id,
            String repo,
            int number,
            String title,
            Instant createdAt,
            int ageDays) {}
}
