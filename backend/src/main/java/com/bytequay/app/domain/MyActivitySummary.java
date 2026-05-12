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

import com.bytequay.app.domain.PrAnalyticsSummary.KpiCard;

import java.util.List;

/**
 * Single payload behind {@code GET /prs/my-activity}. Mirrors the
 * "Dev activity analytics" page from the design doc — what the
 * current user has *done* (opened, merged, etc.) rather than the
 * reviews-side companion page. KPIs that depend on data we don't
 * yet capture surface a {@code pendingNote} the renderer shows in
 * place of a number.
 */
public record MyActivitySummary(
        String scope,
        int watchedRepoCount,
        String currentLogin,
        KpiCard prsOpened,
        KpiCard prsMerged,
        KpiCard commitsMade,
        KpiCard commentsPosted,
        /** One bucket per calendar day in the active scope window
         *  (oldest → newest). {@code opened} counts PRs you authored
         *  whose {@code createdAt} fell on the day; {@code merged}
         *  counts PRs you authored whose {@code mergedAt} fell on
         *  the day. Bucketed in the requested IANA zone. */
        List<DailyAuthored> dailyAuthored,
        /** Top repos by your PR-authoring activity, sorted by total
         *  (opened + merged) desc. */
        List<RepoActivityCount> reposByActivity,
        /** Run of consecutive days ending today (or yesterday if today
         *  is still zero) with at least one contribution. Null when
         *  the contribution calendar isn't available. */
        Integer currentStreakDays,
        /** Longest consecutive-day run anywhere in the calendar window
         *  (~1 year). Null when unavailable. */
        Integer longestStreakDays)
{
    public record DailyAuthored(String date, int opened, int merged) {}

    public record RepoActivityCount(String repo, int prsOpened, int prsMerged) {}
}
