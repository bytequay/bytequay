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
 *
 * @param dailyAuthored one bucket per calendar day in the active scope window.
 * @param reposByActivity top repos by your PR-authoring activity, sorted by
 * total opened and merged count.
 * @param currentStreakDays run of consecutive days ending today, or yesterday
 * if today is still zero.
 * @param longestStreakDays longest consecutive-day run in the calendar window.
 */
public record MyActivitySummary(
        String scope,
        int watchedRepoCount,
        String currentLogin,
        KpiCard prsOpened,
        KpiCard prsMerged,
        KpiCard commitsMade,
        KpiCard commentsPosted,
        List<DailyAuthored> dailyAuthored,
        List<RepoActivityCount> reposByActivity,
        Integer currentStreakDays,
        Integer longestStreakDays)
{
    public record DailyAuthored(String date, int opened, int merged) {}

    public record RepoActivityCount(String repo, int prsOpened, int prsMerged) {}
}
