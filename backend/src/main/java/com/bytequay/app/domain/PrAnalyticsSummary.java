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
 */
public record PrAnalyticsSummary(
        /** "7d" | "30d" | "90d" | "all". Echoes the query param. */
        String scope,
        /** Number of repos the user has explicitly watched. Powers the
         *  "Scope: N watched repos · 90d cap for unwatched" chip. */
        int watchedRepoCount,
        /** GitHub login of the current user, resolved from the cached
         *  settings row. Null when not yet known — the UI renders an
         *  empty state until the next sync populates it. */
        String currentLogin,
        KpiCard prsReviewed,
        KpiCard approvalRate,
        KpiCard linesReviewed,
        KpiCard responseToReviewRequest,
        /** Latest-verdict distribution across PRs you reviewed, ordered
         *  by the canonical slice order (APPROVED, CHANGES_REQUESTED,
         *  COMMENTED, DISMISSED). Partial — same caveat as the KPI
         *  cards. */
        List<OutcomeSlice> reviewOutcomes,
        /** Count of PRs you reviewed bucketed by total line change,
         *  ordered from Tiny → Huge. Partial. */
        List<SizeBucket> sizeDistribution,
        /** Top repos by number of PRs you reviewed, sorted desc.
         *  Capped to the most active handful so the list stays
         *  scannable. Partial. */
        List<RepoReviewCount> reposByReview,
        /** One bucket per calendar day in the active scope window
         *  (oldest → newest). Counts split by review state so the
         *  chart can stack them. Sparse for legacy reviews captured
         *  before V53 — those carry no submission timestamp and are
         *  excluded. */
        List<DailyActivity> dailyActivity,
        /** 7 × 24 heatmap of review counts: outer index 0 = Sunday,
         *  inner index 0 = midnight. Bucketed in the user's local
         *  timezone so the strong-hour band matches their working day.
         *  Reviews without a timestamp are excluded. */
        List<HeatmapCell> reviewHeatmap,
        /** Top co-reviewers — engineers whose reviews land on PRs you
         *  also reviewed. {@code count} is the number of distinct
         *  PRs you both reviewed. Sorted desc; capped. */
        List<CoReviewer> reviewNetwork,
        /** Open PRs you authored that haven't been touched in > 7 days.
         *  Sourced from the {@code pull_requests} row only, so this list
         *  is complete for the local store (not partial). */
        List<StaleAuthoredPr> staleAuthoredPrs)
{
    /**
     * Wire shape for one KPI card. {@code value} is the raw scalar
     * (count or ratio); {@code displayValue} is the rendered string
     * (e.g. "12", "87%", "1,240"). Partial cards depend on cached
     * detail data and may under-count.
     */
    public record KpiCard(
            Double value,
            String displayValue,
            boolean partial,
            /** When non-null, the card renders an empty state with this
             *  copy instead of the value. Used for the "Response to
             *  review request" placeholder until the review mirror
             *  lands. */
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
